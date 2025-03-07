package cromwell.backend.google.pipelines.v2beta

import cloud.nio.impl.drs.DrsCloudNioFileProvider.DrsReadInterpreter
import cloud.nio.impl.drs.DrsCloudNioFileSystemProvider
import com.google.cloud.NoCredentials
import com.typesafe.config.{Config, ConfigFactory}
import common.assertion.CromwellTimeoutSpec
import cromwell.backend.google.pipelines.common.PipelinesApiConfigurationAttributes.GcsTransferConfiguration
import cromwell.backend.google.pipelines.common.PipelinesApiFileInput
import cromwell.backend.google.pipelines.common.action.ActionUtils
import cromwell.backend.google.pipelines.common.io.{DiskType, PipelinesApiWorkingDisk}
import cromwell.core.path.DefaultPathBuilder
import cromwell.filesystems.drs.DrsPathBuilder
import eu.timepit.refined.refineMV
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

class PipelinesConversionsSpec extends AnyFlatSpec with CromwellTimeoutSpec with Matchers {

  behavior of "PipelinesConversions"
  implicit val gcsTransferConfiguration: GcsTransferConfiguration =
    GcsTransferConfiguration(transferAttempts = refineMV(1), parallelCompositeUploadThreshold = "0")

  private val marthaConfig: Config = ConfigFactory.parseString(
    """martha {
      |   url = "http://matha-url"
      |}
      |""".stripMargin
  )

  private lazy val fakeCredentials = NoCredentials.getInstance

  private val drsReadInterpreter: DrsReadInterpreter = (_, _) =>
    throw new UnsupportedOperationException("Currently PipelinesConversionsSpec doesn't need to use drs read interpreter.")

  it should "create a DRS input parameter" in {

    val drsPathBuilder = DrsPathBuilder(
      new DrsCloudNioFileSystemProvider(marthaConfig, fakeCredentials, drsReadInterpreter),
      None,
    )
    val drsPath = drsPathBuilder.build("drs://drs.example.org/aaaabbbb-cccc-dddd-eeee-abcd0000dcba").get
    val containerRelativePath = DefaultPathBuilder.get("path/to/file.bai")
    val mount = PipelinesApiWorkingDisk(DiskType.LOCAL, 1)
    val input = PipelinesApiFileInput("example", drsPath, containerRelativePath, mount)
    val actions = PipelinesConversions.inputToParameter.toActions(input, Nil)
    actions.size should be(2)

    val logging = actions.head

    logging.keySet.asScala should contain theSameElementsAs
      Set("entrypoint", "commands", "imageUri", "labels", "mounts", "timeout")

    logging.get("commands") should be(a[java.util.List[_]])
    logging.get("commands").asInstanceOf[java.util.List[_]] should contain(
      """printf '%s %s\n' "$(date -u '+%Y/%m/%d %H:%M:%S')" """ +
        """Localizing\ input\ drs://drs.example.org/aaaabbbb-cccc-dddd-eeee-abcd0000dcba\ """ +
        """-\>\ /cromwell_root/path/to/file.bai"""
    )

    logging.get("mounts") should be(a[java.util.List[_]])
    logging.get("mounts").asInstanceOf[java.util.List[_]] should be (empty)

    logging.get("imageUri") should be(ActionUtils.CloudSdkImage)

    val loggingLabels = logging.get("labels").asInstanceOf[java.util.Map[_, _]]
    loggingLabels.keySet.asScala should contain theSameElementsAs List("logging", "inputName")
    loggingLabels.get("logging") should be("Localization")
    loggingLabels.get("inputName") should be("example")

    val action = actions.tail.head

    action.keySet.asScala should contain theSameElementsAs
      Set("commands", "environment", "imageUri", "labels", "mounts")

    action.get("commands") should be(a[java.util.List[_]])
    action.get("commands").asInstanceOf[java.util.List[_]] should contain theSameElementsAs List(
      "drs://drs.example.org/aaaabbbb-cccc-dddd-eeee-abcd0000dcba",
      "/cromwell_root/path/to/file.bai"
    )

    action.get("mounts") should be(a[java.util.List[_]])
    action.get("mounts").asInstanceOf[java.util.List[_]] should be (empty)

    action.get("imageUri") should be("somerepo/drs-downloader:tagged")

    val actionLabels = action.get("labels").asInstanceOf[java.util.Map[_, _]]
    actionLabels.keySet.asScala should contain theSameElementsAs List("tag", "inputName")
    actionLabels.get("tag") should be("Localization")
    actionLabels.get("inputName") should be("example")
  }

}
