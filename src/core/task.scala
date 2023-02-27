package parasitism

import anticipation.*
import rudiments.*
import deviation.*

@capability
@missingContext(contextMessage(module = "parasitism", typeclass = "Monitor")())
sealed trait Monitor:
  def id: Text
  def virtualThreads: Boolean
  def daemon: Boolean
  private var continue: Boolean = true

  final def relinquish(cleanup: => Unit): Unit =
    unsafely(if !continue then cleanup.pipe((throw CancelError()).waive))

  def fork(runnable: Runnable, id: Text): Thread =
    if virtualThreads then Thread.ofVirtual.nn.name(id.s).nn.start(runnable).nn
    else Thread(runnable, id.s).nn.tap(_.setDaemon(daemon)).tap(_.start())
  
  def child(childId: Text): TaskMonitor = TaskMonitor(Text(id.s+"/"+childId.s), this)

package monitors:
  given global: Monitor = new Monitor:
    def id: Text = Text("")
    def virtualThreads: Boolean = false
    def daemon: Boolean = true

@capability
case class TaskMonitor(id: Text, parent: Monitor) extends Monitor:
  def virtualThreads: Boolean = parent.virtualThreads
  def daemon: Boolean = parent.daemon

@capability
case class Supervisor(id: Text, virtualThreads: Boolean, daemon: Boolean) extends Monitor

object Task:
  def apply[ResultType](name: Text)(eval: /*(m: Monitor) ?=> {m} ResultType*/ Monitor ?=> ResultType)(using monitor: Monitor): Task[ResultType] =
    new Task[ResultType](name, m => eval(using m), monitor.child(name))

class Task
    [+ResultType]
    (val id: Text, val eval: Monitor => /*(m: {*} Monitor) => {m}*/ ResultType, monitor: Monitor):
  private final val promise: Promise[/*{eval, monitor}*/ ResultType] = Promise()

  private val runnable: Runnable = new Runnable():
    def run(): Unit =
      try promise.supply(eval(monitor))
      catch
        case err: AlreadyCompleteError => ()
        case err: CancelError          => ()

  val thread: Thread = Thread(runnable, id.s).nn.tap(_.start())
  
  def attend()(using cancel: CanThrow[CancelError]): Unit = thread.join()
  
  def await()(using cancel: CanThrow[CancelError]): ResultType =
    promise.await().tap(thread.join().waive)
  
  def cancel(): Unit = thread.interrupt().tap:
    (try promise.cancel() catch case err: CancelError => ()).waive
  
  def await
      [DurationType](duration: DurationType)(using GenericDuration[DurationType], Monitor)
      (using cancel: CanThrow[CancelError], timeout: CanThrow[TimeoutError])
      : ResultType =
    promise.await(duration).tap(thread.join().waive)

  def map[ResultType2](fn: ResultType => ResultType2)(using Monitor, CanThrow[CancelError]): Task[ResultType2] =
    Task(Text(id.s+".map"))(fn(await()))
  
  def flatMap
      [ResultType2](fn: ResultType => Task[ResultType2])(using Monitor, CanThrow[CancelError])
      : Task[ResultType2] =
    Task(Text(id.s+".flatMap"))(fn(await()).await())

def sleep[DurationType](using GenericDuration[DurationType])(time: DurationType)(using Monitor): Unit =
  try Thread.sleep(readDuration(time)) catch case err: InterruptedException => unsafely(throw CancelError())

def relinquish(cleanup: => Unit = ())(using mon: Monitor): Unit =
  import unsafeExceptions.canThrowAny
  mon.relinquish(cleanup)

def supervise
    [ResultType](id: Text, virtualThreads: Boolean = false, daemon: Boolean = false)
    (fn: Supervisor ?=> ResultType): ResultType =
  val supervisor = Supervisor(id, virtualThreads, daemon)
  fn(using supervisor)

extension [ResultType](xs: Iterable[Task[ResultType]])
  transparent inline def sequence(using monitor: Monitor): Task[Iterable[ResultType]] throws CancelError =
    Task(Text("sequence"))(xs.map(_.await()))