package com.github.kuhnen.master

/**
 * Created by kuhnen on 12/17/14.
 */

import akka.actor.SupervisorStrategy.{Stop, Escalate, Restart}
import akka.actor._
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.event.LoggingReceive
import com.github.kuhnen.cluster.ClusterConfig
import com.github.kuhnen.master.MasterWorkerProtocol.{Register, RegisterWorkerOnCluster, Registered, Unregister}
import com.github.kuhnen.master.kafka.KafkaTopicWatcherActor
import com.github.kuhnen.master.kafka.KafkaTopicWatcherActor.TopicsAvailable

import scala.concurrent.duration._

object MasterActor {

  type ActorBuilder = (ActorRefFactory, Option[String]) => ActorRef

  def props(topicWatcherMaker: ActorBuilder,
            coordinatorActorMaker: ActorBuilder) = Props(classOf[MasterActor], topicWatcherMaker, coordinatorActorMaker)

  val topicWatcherInterval = ClusterConfig.watcherInterval
  val topicWatcherInitialDelay = ClusterConfig.watcherInitialDelay

}

object MasterWorkerProtocol {

  final case class RegisterWorkerOnCluster(work: ActorRef)

  final case class Register(worker: ActorRef)

  final case class Unregister(worker: ActorRef)

  case object Registered

  //TODO
  case class Subscribe(worker: ActorRef, topic: String)

}


class MasterActor(topicWatcherMaker: (ActorRefFactory, Option[String]) => ActorRef,
                  coordinatorActorMaker: (ActorRefFactory, Option[String]) => ActorRef) extends Actor with ActorLogging {

  import com.github.kuhnen.master.MasterActor._

  implicit val ec = context.system.dispatcher
  //val mediator: ActorRef = DistributedPubSubExtension(context.system).mediator

  var topicWatcher: ActorRef = _
  var coordinatorActor: ActorRef = _
  var topicsCancellable: Cancellable = _

  //TODO  should be able to recover if zookeeper is out
  override val supervisorStrategy = OneForOneStrategy(
    maxNrOfRetries = 1,
    withinTimeRange = 10 seconds,
    loggingEnabled = true) {
    case ActorInitializationException(actor, message, throwable) =>
      log.error(s"$actor not able to Initialize.\n Message: $message. \n Error: ${throwable.getMessage}")
      context.system.shutdown()
      Stop

    case e =>
      log.error("Unexpected failure: {}", e.getMessage)
      Escalate
  }

  override def preStart(): Unit = {

    ClusterReceptionistExtension(context.system).registerService(self)
    //topicWatcher = context.actorOf(topicsWatcher, name = "kafka-topic-watcher")
    topicWatcher = topicWatcherMaker(context, Option("topicWatcher"))
    //coordinatorActor = context.actorOf(WorkersCoordinator.props(), name = "WorkersCoodinator")
    coordinatorActor = coordinatorActorMaker(context, Option("coordinator"))
    topicsCancellable = context.system.scheduler.schedule(topicWatcherInitialDelay, topicWatcherInitialDelay, topicWatcher, KafkaTopicWatcherActor.GetTopics)


  }

  override def postStop(): Unit = {
    topicsCancellable.cancel()
  }


  def receive = LoggingReceive {

    case RegisterWorkerOnCluster(worker) =>
      //We assume that the coordinator is always local with the master
      coordinatorActor ! Register(worker)
      context.watch(worker)
      sender() ! Registered

    //TODO,  the topic watcher  should be a child of the coordinator
    case TopicsAvailable(topics) => coordinatorActor ! TopicsAvailable(topics)

    case Terminated(worker) => Unregister(worker)


  }

  //class Master(workTimeout: FiniteDuration) extends PersistentActor with ActorLogging {

  //import WorkState._


  /*
    // persistenceId must include cluster role to support multiple masters
    override def persistenceId: String = Cluster(context.system).selfRoles.find(_.startsWith("backend-")) match {
      case Some(role) ⇒ role + "-master"
      case None       ⇒ "master"
    }

    // workers state is not event sourced
    private var workers = Map[String, WorkerState]()

    // workState is event sourced
    private var workState = WorkState.empty

    import context.dispatcher
    val cleanupTask = context.system.scheduler.schedule(workTimeout / 2, workTimeout / 2,
      self, CleanupTick)

    override def postStop(): Unit = cleanupTask.cancel()

    override def receiveRecover: Receive = {
      case event: WorkDomainEvent =>
        // only update current state by applying the event, no side effects
        workState = workState.updated(event)
        log.info("Replayed {}", event.getClass.getSimpleName)
    }

    override def receiveCommand: Receive = {
      case MasterWorkerProtocol.RegisterWorker(workerId) =>
        if (workers.contains(workerId)) {
          workers += (workerId -> workers(workerId).copy(ref = sender()))
        } else {
          log.info("Worker registered: {}", workerId)
          workers += (workerId -> WorkerState(sender(), status = Idle))
          if (workState.hasWork)
            sender() ! MasterWorkerProtocol.WorkIsReady
        }

      case MasterWorkerProtocol.WorkerRequestsWork(workerId) =>
        if (workState.hasWork) {
          workers.get(workerId) match {
            case Some(s @ WorkerState(_, Idle)) =>
              val work = workState.nextWork
              persist(WorkStarted(work.workId)) { event =>
                workState = workState.updated(event)
                log.info("Giving worker {} some work {}", workerId, work.workId)
                workers += (workerId -> s.copy(status = Busy(work.workId, Deadline.now + workTimeout)))
                sender() ! work
              }
            case _ =>
          }
        }

      case MasterWorkerProtocol.WorkIsDone(workerId, workId, result) =>
        // idempotent
        if (workState.isDone(workId)) {
          // previous Ack was lost, confirm again that this is done
          sender() ! MasterWorkerProtocol.Ack(workId)
        } else if (!workState.isInProgress(workId)) {
          log.info("Work {} not in progress, reported as done by worker {}", workId, workerId)
        } else {
          log.info("Work {} is done by worker {}", workId, workerId)
          changeWorkerToIdle(workerId, workId)
          persist(WorkCompleted(workId, result)) { event ⇒
            workState = workState.updated(event)
            mediator ! DistributedPubSubMediator.Publish(ResultsTopic, WorkResult(workId, result))
            // Ack back to original sender
            sender ! MasterWorkerProtocol.Ack(workId)
          }
        }

      case MasterWorkerProtocol.WorkFailed(workerId, workId) =>
        if (workState.isInProgress(workId)) {
          log.info("Work {} failed by worker {}", workId, workerId)
          changeWorkerToIdle(workerId, workId)
          persist(WorkerFailed(workId)) { event ⇒
            workState = workState.updated(event)
            notifyWorkers()
          }
        }

      case work: Work =>
        // idempotent
        if (workState.isAccepted(work.workId)) {
          sender() ! Master.Ack(work.workId)
        } else {
          log.info("Accepted work: {}", work.workId)
          persist(WorkAccepted(work)) { event ⇒
            // Ack back to original sender
            sender() ! Master.Ack(work.workId)
            workState = workState.updated(event)
            notifyWorkers()
          }
        }

      case CleanupTick =>
        for ((workerId, s @ WorkerState(_, Busy(workId, timeout))) ← workers) {
          if (timeout.isOverdue) {
            log.info("Work timed out: {}", workId)
            workers -= workerId
            persist(WorkerTimedOut(workId)) { event ⇒
              workState = workState.updated(event)
              notifyWorkers()
            }
          }
        }
    }

    def notifyWorkers(): Unit =
      if (workState.hasWork) {
        // could pick a few random instead of all
        workers.foreach {
          case (_, WorkerState(ref, Idle)) => ref ! MasterWorkerProtocol.WorkIsReady
          case _                           => // busy
        }
      }

    def changeWorkerToIdle(workerId: String, workId: String): Unit =
      workers.get(workerId) match {
        case Some(s @ WorkerState(_, Busy(`workId`, _))) ⇒
          workers += (workerId -> s.copy(status = Idle))
        case _ ⇒
        // ok, might happen after standby recovery, worker state is not persisted
      }

    // TODO cleanup old workers
    // TODO cleanup old workIds, doneWorkIds
  */
}