package cromwell

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.testkit._
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import cromwell.CromwellTestKitSpec._
import cromwell.core._
import cromwell.core.path.BetterFileMethods.Cmds
import cromwell.core.path.DefaultPathBuilder
import cromwell.docker.DockerInfoActor.{DockerInfoSuccessResponse, DockerInformation}
import cromwell.docker.{DockerHashResult, DockerInfoRequest}
import cromwell.engine.MockCromwellTerminator
import cromwell.engine.backend.{BackendConfiguration, CromwellBackends}
import cromwell.engine.workflow.WorkflowManagerActor.RetrieveNewWorkflows
import cromwell.engine.workflow.lifecycle.execution.callcaching.CallCacheReadActor.{CacheLookupNoHit, CacheLookupRequest}
import cromwell.engine.workflow.lifecycle.execution.callcaching.CallCacheWriteActor.SaveCallCacheHashes
import cromwell.engine.workflow.lifecycle.execution.callcaching.CallCacheWriteSuccess
import cromwell.engine.workflow.workflowstore.WorkflowStoreSubmitActor.WorkflowSubmittedToStore
import cromwell.engine.workflow.workflowstore.{InMemorySubWorkflowStore, InMemoryWorkflowStore, WorkflowStoreActor}
import cromwell.jobstore.JobStoreActor.{JobStoreWriteSuccess, JobStoreWriterCommand}
import cromwell.languages.config.{CromwellLanguages, LanguageConfiguration}
import cromwell.server.{CromwellRootActor, CromwellSystem}
import cromwell.services.metadata.MetadataService._
import cromwell.services.{FailedMetadataJsonResponse, MetadataJsonResponse, ServiceRegistryActor, SuccessfulMetadataJsonResponse}
import cromwell.subworkflowstore.EmptySubWorkflowStoreActor
import cromwell.util.SampleWdl
import org.scalactic.Equality
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.enablers.Retrying
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._
import wom.core.FullyQualifiedName
import wom.types._
import wom.values._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.matching.Regex

case class OutputNotFoundException(outputFqn: String, actualOutputs: String) extends RuntimeException(s"Expected output $outputFqn was not found in: '$actualOutputs'")
case class LogNotFoundException(log: String) extends RuntimeException(s"Expected log $log was not found")

object CromwellTestKitSpec {

  val ConfigText: String =
    """
      |akka {
      |  loggers = ["akka.testkit.TestEventListener"]
      |  loglevel = "INFO"
      |  actor {
      |    debug {
      |       receive = on
      |    }
      |  }
      |  dispatchers {
      |    # A dispatcher for actors performing blocking io operations
      |    # Prevents the whole system from being slowed down when waiting for responses from external resources for instance
      |    io-dispatcher {
      |      type = Dispatcher
      |      executor = "fork-join-executor"
      |      # Using the forkjoin defaults, this can be tuned if we wish
      |    }
      |
      |    # A dispatcher for actors handling API operations
      |    # Keeps the API responsive regardless of the load of workflows being run
      |    api-dispatcher {
      |      type = Dispatcher
      |      executor = "fork-join-executor"
      |    }
      |
      |    # A dispatcher for engine actors
      |    # Because backends behavior is unpredictable (potentially blocking, slow) the engine runs
      |    # on its own dispatcher to prevent backends from affecting its performance.
      |    engine-dispatcher {
      |      type = Dispatcher
      |      executor = "fork-join-executor"
      |    }
      |
      |    # A dispatcher used by supported backend actors
      |    backend-dispatcher {
      |      type = Dispatcher
      |      executor = "fork-join-executor"
      |    }
      |
      |    # Note that without further configuration, backend actors run on the default dispatcher
      |  }
      |  test {
      |    # Some of our tests fire off a message, then expect a particular event message within 3s (the default).
      |    # Especially on CI, the metadata test does not seem to be returning in time. So, overriding the timeouts
      |    # with slightly higher values. Alternatively, could also adjust the akka.test.timefactor only in CI.
      |    filter-leeway = 60s
      |    single-expect-default = 5s
      |    default-timeout = 10s
      |  }
      |}
      |
      |services {}
    """.stripMargin

  val TimeoutDuration: FiniteDuration = 60.seconds

  class TestWorkflowManagerSystem(testActorSystem: ActorSystem, override val config: Config) extends CromwellSystem {
    override protected def systemName: String = testActorSystem.name
    override protected def newActorSystem(): ActorSystem = testActorSystem
    /**
      * Do NOT shut down the test actor system inside the normal flow.
      * The actor system will be externally shutdown outside the block.
      */
    // -Ywarn-value-discard
    override def shutdownActorSystem(): Future[Terminated] = { Future.successful(null) }
  }

  /**
   * Wait for exactly one occurrence of the specified info pattern in the specified block.  The block is in its own
   * parameter list for usage syntax reasons.
   */
  def waitForInfo[T](pattern: String, occurrences: Int = 1)(block: => T)(implicit system: ActorSystem): T = {
    EventFilter.info(pattern = pattern, occurrences = occurrences).intercept {
      block
    }
  }

  /**
   * Wait for occurrence(s) of the specified warning pattern in the specified block.  The block is in its own parameter
   * list for usage syntax reasons.
   */
  def waitForWarning[T](pattern: String, occurrences: Int = 1)(block: => T)(implicit system: ActorSystem): T = {
    EventFilter.warning(pattern = pattern, occurrences = occurrences).intercept {
      block
    }
  }

  /**
   * Akka TestKit appears to be unable to match errors generated by `log.error(Throwable, String)` with the normal
   * `EventFilter.error(...).intercept {...}` mechanism since `EventFilter.error` forces the use of a dummy exception
   * that never matches a real exception.  This method works around that problem by building an `ErrorFilter` more
   * explicitly to allow the caller to specify a `Throwable` class.
   */
  def waitForErrorWithException[T](pattern: String,
                                   throwableClass: Class[_ <: Throwable] = classOf[Throwable],
                                   occurrences: Int = 1)
                                  (block: => T)
                                  (implicit system: ActorSystem): T = {
    val regex = Right[String, Regex](pattern.r)
    ErrorFilter(throwableClass, source = None, message = regex, complete = false)(occurrences = occurrences).intercept {
      block
    }
  }


  /**
    * Special case for validating outputs. Used when the test wants to check that an output exists, but doesn't care what
    * the actual value was.
    */
  lazy val AnyValueIsFine: WomValue = WomString("Today you are you! That is truer than true! There is no one alive who is you-er than you!")

  def replaceVariables(womValue: WomValue, workflowId: WorkflowId): WomValue = {
    womValue match {
      case WomString(value) => WomString(replaceVariables(value, workflowId))
      case _ => womValue
    }
  }

  def replaceVariables(value: String, workflowId: WorkflowId): String = {
    val variables = Map("PWD" -> Cmds.pwd, "UUID" -> workflowId)
    variables.foldLeft(value) {
      case (result, (variableName, variableValue)) => result.replace(s"<<$variableName>>", s"$variableValue")
    }
  }

  lazy val DefaultConfig: Config = ConfigFactory.load

  lazy val NooPServiceActorConfig: Config = DefaultConfig.withValue(
    "services.LoadController.class", ConfigValueFactory.fromAnyRef("cromwell.services.NooPServiceActor")
  )

  lazy val JesBackendConfig: Config = ConfigFactory.parseString(
    """
      |{
      |  // Google project
      |  project = "my-cromwell-workflows"
      |
      |  // Base bucket for workflow executions
      |  root = "gs://my-cromwell-workflows-bucket"
      |
      |  // Polling for completion backs-off gradually for slower-running jobs.
      |  // This is the maximum polling interval (in seconds):
      |  maximum-polling-interval = 600
      |
      |  // Optional Dockerhub Credentials. Can be used to access private docker images.
      |  dockerhub {
      |    // account = ""
      |    // token = ""
      |  }
      |
      |  genomics {
      |    // A reference to an auth defined in the `google` stanza at the top.  This auth is used to create
      |    // Pipelines and manipulate auth JSONs.
      |    auth = "service-account"
      |
      |    // Endpoint for APIs, no reason to change this unless directed by Google.
      |    endpoint-url = "https://genomics.googleapis.com/"
      |  }
      |
      |  filesystems {
      |    gcs {
      |      // A reference to a potentially different auth for manipulating files via engine functions.
      |      auth = "user-via-refresh"
      |    }
      |  }
      |}
    """.stripMargin)

  /**
    * It turns out that tests using the metadata refresh actor running in parallel don't work so well if there are more
    * than one refresh actor. After many fits and starts the author decided that discretion was the better part of valor
    * as it is difficult to only singleton-ize the refresher or even the metadata service itself.
    *
    * Instead, doing things the "old way" for the tests, setting up a separate actor system and a singleton service
    * registry - this will be used by the CromwellRootActor within tests.
    *
    * :'(
    */
  private val ServiceRegistryActorSystem = akka.actor.ActorSystem("cromwell-service-registry-system")

  val ServiceRegistryActorInstance: ActorRef = {
    ServiceRegistryActorSystem.actorOf(ServiceRegistryActor.props(CromwellTestKitSpec.DefaultConfig), "ServiceRegistryActor")
  }

  class TestCromwellRootActor(config: Config)(implicit materializer: ActorMaterializer)
    extends CromwellRootActor(MockCromwellTerminator, false, false, serverMode = true, config = config) {

    override lazy val serviceRegistryActor: ActorRef = ServiceRegistryActorInstance
    override lazy val workflowStore = new InMemoryWorkflowStore
    override lazy val subWorkflowStore = new InMemorySubWorkflowStore(workflowStore)
    def submitWorkflow(sources: WorkflowSourceFilesCollection): WorkflowId = {
      val submitMessage = WorkflowStoreActor.SubmitWorkflow(sources)
      val result = Await.result(workflowStoreActor.ask(submitMessage)(TimeoutDuration), Duration.Inf).asInstanceOf[WorkflowSubmittedToStore].workflowId
      workflowManagerActor ! RetrieveNewWorkflows
      result
    }
  }
}

abstract class CromwellTestKitWordSpec extends CromwellTestKitSpec with AnyWordSpecLike
abstract class CromwellTestKitSpec extends TestKitSuite
  with DefaultTimeout with ImplicitSender with Matchers with ScalaFutures with Eventually with OneInstancePerTest {

  override protected lazy val actorSystemConfig: Config = ConfigFactory.parseString(CromwellTestKitSpec.ConfigText)

  lazy val twms: TestWorkflowManagerSystem =
    new CromwellTestKitSpec.TestWorkflowManagerSystem(system, CromwellTestKitSpec.DefaultConfig)

  /*
  These singletons must be initialized before a lot of other work is done, otherwise their `var mySingleton = _`
  end up causing cryptic NullPointerException errors.

  Initializing them (repeatedly) here is good enough for tests.
   */
  CromwellBackends.initBackends(BackendConfiguration.AllBackendEntries)
  CromwellLanguages.initLanguages(LanguageConfiguration.AllLanguageEntries)

  implicit lazy val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(200, Seconds), interval = Span(1000, Millis))
  implicit lazy val ec: ExecutionContext = system.dispatcher
  implicit lazy val materializer: ActorMaterializer = twms.materializer
  lazy val dummyServiceRegistryActor: ActorRef = system.actorOf(Props.empty, "dummyServiceRegistryActor")
  lazy val dummyLogCopyRouter: ActorRef = system.actorOf(Props.empty, "dummyLogCopyRouter")

  //noinspection ConvertExpressionToSAM
  // Allow to use shouldEqual between 2 WdlTypes while acknowledging for edge cases
  implicit val wdlTypeSoftEquality: Equality[WomType] = new Equality[WomType] {
    @tailrec
    override def areEqual(a: WomType, b: Any): Boolean = (a, b) match {
      case (WomStringType | WomSingleFileType, WomSingleFileType | WomStringType) => true
      case (arr1: WomArrayType, arr2: WomArrayType) => areEqual(arr1.memberType, arr2.memberType)
      case (map1: WomMapType, map2: WomMapType) => areEqual(map1.valueType, map2.valueType)
      case _ => a == b
    }
  }

  // Allow to use shouldEqual between 2 WdlValues while acknowledging for edge cases and checking for WomType compatibility
  implicit val wdlEquality: Equality[WomValue] = new Equality[WomValue] {
    def fileEquality(f1: String, f2: String): Boolean =
      DefaultPathBuilder.get(f1).getFileName == DefaultPathBuilder.get(f2).getFileName

    override def areEqual(a: WomValue, b: Any): Boolean = {
      val typeEquality = b match {
        case v: WomValue => wdlTypeSoftEquality.areEqual(a.womType, v.womType)
        case _ => false
      }

      val valueEquality = (a, b) match {
        case (_: WomFile, expectedFile: WomFile) => fileEquality(a.valueString, expectedFile.valueString)
        case (_: WomString, expectedFile: WomFile) => fileEquality(a.valueString, expectedFile.valueString)
        case (array: WomArray, expectedArray: WomArray) =>
          (array.value.length == expectedArray.value.length) &&
            array.value.zip(expectedArray.value).map(Function.tupled(areEqual)).forall(identity)
        case (map: WomMap, expectedMap: WomMap) =>
          val mapped = map.value.map {
            case (k, v) => expectedMap.value.contains(k) && areEqual(v, expectedMap.value(k))
          }

          (map.value.size == expectedMap.value.size) && mapped.forall(identity)
        case _ => a == b
      }

      typeEquality && valueEquality
    }
  }

  protected def buildCromwellRootActor(config: Config, actorName: String): TestActorRef[TestCromwellRootActor] = {
    TestActorRef(new TestCromwellRootActor(config), actorName)
  }

  def runWdl(sampleWdl: SampleWdl,
             runtime: String = "",
             workflowOptions: String = "{}",
             customLabels: String = "{}",
             terminalState: WorkflowState = WorkflowSucceeded,
             config: Config = NooPServiceActorConfig,
             patienceConfig: PatienceConfig = defaultPatience,
             testActorName: String,
            )(implicit ec: ExecutionContext): Map[FullyQualifiedName, WomValue] = {

    val rootActor = buildCromwellRootActor(config, testActorName)
    val sources = sampleWdl.asWorkflowSources(
      runtime = runtime,
      workflowOptions = workflowOptions,
      workflowType = Option("WDL"),
      workflowTypeVersion = None,
      labels = customLabels
    )
    val workflowId = rootActor.underlyingActor.submitWorkflow(sources)
    eventually { verifyWorkflowComplete(rootActor.underlyingActor.serviceRegistryActor, workflowId) } (config = patienceConfig, pos = implicitly[org.scalactic.source.Position], retrying = implicitly[Retrying[Unit]])
    verifyWorkflowState(rootActor.underlyingActor.serviceRegistryActor, workflowId, terminalState)

    val outcome = getWorkflowOutputsFromMetadata(workflowId, rootActor.underlyingActor.serviceRegistryActor)
    system.stop(rootActor)
    // And return the outcome:
    outcome
  }

  def runWdlAndAssertOutputs(sampleWdl: SampleWdl,
                             expectedOutputs: Map[FullyQualifiedName, WomValue],
                             runtime: String = "",
                             workflowOptions: String = "{}",
                             allowOtherOutputs: Boolean = true,
                             config: Config = NooPServiceActorConfig,
                             patienceConfig: PatienceConfig = defaultPatience,
                             testActorName: String,
                            )
                            (implicit ec: ExecutionContext): WorkflowId = {
    val rootActor = buildCromwellRootActor(config, testActorName)
    val sources = sampleWdl.asWorkflowSources(runtime, workflowOptions)

    val workflowId = rootActor.underlyingActor.submitWorkflow(sources)
    eventually { verifyWorkflowComplete(rootActor.underlyingActor.serviceRegistryActor, workflowId) } (config = patienceConfig, pos = implicitly[org.scalactic.source.Position], retrying = implicitly[Retrying[Unit]])
    verifyWorkflowState(rootActor.underlyingActor.serviceRegistryActor, workflowId, WorkflowSucceeded)

    val outputs = getWorkflowOutputsFromMetadata(workflowId, rootActor.underlyingActor.serviceRegistryActor)
    val actualOutputNames = outputs.keys mkString ", "
    val expectedOutputNames = expectedOutputs.keys mkString " "

    expectedOutputs foreach { case (outputFqn, expectedValue) =>
      val actualValue = outputs.getOrElse(outputFqn, throw OutputNotFoundException(outputFqn, actualOutputNames))
      if (expectedValue != AnyValueIsFine) actualValue shouldEqual replaceVariables(expectedValue, workflowId)
    }
    if (!allowOtherOutputs) {
      outputs foreach { case (actualFqn, actualValue) =>
        val expectedValue = expectedOutputs.getOrElse(actualFqn, throw new RuntimeException(s"Actual output $actualFqn was not wanted in '$expectedOutputNames'"))
        if (expectedValue != AnyValueIsFine) actualValue shouldEqual expectedValue
      }
    }

    system.stop(rootActor)
    workflowId
  }

  private def getWorkflowState(workflowId: WorkflowId, serviceRegistryActor: ActorRef)(implicit ec: ExecutionContext): WorkflowState = {
    val statusResponse = serviceRegistryActor.ask(GetStatus(workflowId))(TimeoutDuration).collect {
      case SuccessfulMetadataJsonResponse(_, jsObject) => WorkflowState.withName(jsObject.fields("status").asInstanceOf[JsString].value)
      case f => throw new RuntimeException(s"Unexpected status response for $workflowId: $f")
    }
    Await.result(statusResponse, TimeoutDuration)
  }

  /**
    * Verifies that a workflow is complete
    */
  protected def verifyWorkflowComplete(serviceRegistryActor: ActorRef, workflowId: WorkflowId)(implicit ec: ExecutionContext): Unit = {
    List(WorkflowSucceeded, WorkflowFailed, WorkflowAborted) should contain(getWorkflowState(workflowId, serviceRegistryActor))
    ()
  }

  /**
    * Verifies that a state is correct.
    */
  protected def verifyWorkflowState(serviceRegistryActor: ActorRef, workflowId: WorkflowId, expectedState: WorkflowState)(implicit ec: ExecutionContext): Unit = {
    getWorkflowState(workflowId, serviceRegistryActor) should equal (expectedState)
    ()
  }

  private def getWorkflowOutputsFromMetadata(id: WorkflowId, serviceRegistryActor: ActorRef): Map[FullyQualifiedName, WomValue] = {

    val response = serviceRegistryActor.ask(WorkflowOutputs(id)).mapTo[MetadataJsonResponse] collect {
      case SuccessfulMetadataJsonResponse(_, r) => r
      case FailedMetadataJsonResponse(_, e) => throw e
    }
    val jsObject = Await.result(response, TimeoutDuration)

    jsObject.getFields(WorkflowMetadataKeys.Outputs).toList match {
      case head::_ => head.asInstanceOf[JsObject].fields.map( x => (x._1, jsValueToWdlValue(x._2)))
      case _ => Map.empty
    }
  }

  private def jsValueToWdlValue(jsValue: JsValue): WomValue = {
    jsValue match {
      case str: JsString => WomString(str.value)
      case JsNumber(number) if number.scale == 0 => WomInteger(number.intValue)
      case JsNumber(number) => WomFloat(number.doubleValue)
      case JsBoolean(bool) => WomBoolean(bool)
      case array: JsArray =>
        val valuesArray = array.elements.map(jsValueToWdlValue)
        if (valuesArray.isEmpty) WomArray(WomArrayType(WomStringType), Seq.empty)
        else WomArray(WomArrayType(valuesArray.head.womType), valuesArray)
      case map: JsObject =>
        // TODO: currently assuming all keys are String. But that's not WDL-complete...
        val valuesMap: Map[WomValue, WomValue] = map.fields.map { case (fieldName, fieldValue) => (WomString(fieldName), jsValueToWdlValue(fieldValue)) }
        if (valuesMap.isEmpty) WomMap(WomMapType(WomStringType, WomStringType), Map.empty)
        else WomMap(WomMapType(WomStringType, valuesMap.head._2.womType), valuesMap)
    }
  }
}

class AlwaysHappyJobStoreActor extends Actor {
  override def receive: Receive = {
    case x: JobStoreWriterCommand => sender ! JobStoreWriteSuccess(x)
  }
}

object AlwaysHappySubWorkflowStoreActor {
  def props: Props = Props(new EmptySubWorkflowStoreActor)
}

object AlwaysHappyJobStoreActor {
  def props: Props = Props(new AlwaysHappyJobStoreActor)
}

class EmptyCallCacheReadActor extends Actor {
  override def receive: Receive = {
    case _: CacheLookupRequest => sender ! CacheLookupNoHit
  }
}

class EmptyCallCacheWriteActor extends Actor {
  override def receive: Receive = {
    case SaveCallCacheHashes => sender ! CallCacheWriteSuccess
  }
}

object EmptyCallCacheReadActor {
  def props: Props = Props(new EmptyCallCacheReadActor)
}

object EmptyCallCacheWriteActor {
  def props: Props = Props(new EmptyCallCacheWriteActor)
}

class EmptyDockerHashActor extends Actor {
  override def receive: Receive = {
    case request: DockerInfoRequest => sender ! DockerInfoSuccessResponse(DockerInformation(DockerHashResult("alg", "hash"), None), request)
  }
}

object EmptyDockerHashActor {
  def props: Props = Props(new EmptyDockerHashActor)
}
