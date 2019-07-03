package bloop.dap

import java.net.{InetSocketAddress, Socket, SocketException}

import bloop.dap.DebugSession._
import monix.execution.atomic.Atomic
import com.microsoft.java.debug.core.adapter.{ProtocolServer => DapServer}
import com.microsoft.java.debug.core.protocol.Messages.{Request, Response}
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.{Events, JsonUtils}
import monix.eval.Task
import monix.execution.{Cancelable, CancelableFuture, Scheduler}

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.Try

/**
 * Instead of relying on a standard handler for the 'launch' request, this class starts a [[debuggee]] in the background
 * and then attaches to it as if it were a remote process. It also kills the [[debuggee]] upon receiving 'disconnect' request
 */
final class DebugSession(
    socket: Socket,
    startDebuggee: DebugSessionLogger => Task[Unit],
    ioScheduler: Scheduler
) extends DapServer(socket.getInputStream, socket.getOutputStream, DebugExtensions.newContext)
    with Cancelable {
  type LaunchId = Int
  private val launches = mutable.Set.empty[LaunchId]

  private val debugAddress = Promise[InetSocketAddress]()
  private val exitStatusPromise = Promise[ExitStatus]()
  private val debuggeeExited = Promise[Unit]()

  private val isStarted = Atomic(false) // if true - the server was already started
  private val runningDebuggee = Atomic(CancelableFuture.unit)

  def exitStatus(): Task[ExitStatus] = {
    Task.fromFuture(exitStatusPromise.future)
  }

  /**
   * requests the debugge to stop. Once that happen, requests the server to disconnect.
   * When handling the response to this request, the server should close the socket
   */
  def cancel(): Unit = {
    runningDebuggee.get.cancel()
    Task
      .fromFuture(debuggeeExited.future)
      .map(_ => disconnectRequest(InternalRequestId))
      .foreachL(dispatchRequest)
      .runAsync(ioScheduler)
  }

  /**
   * Makes the parent [[run()]] method non-blocking.
   * Schedules a run-only-once task which:
   * 1. starts the debuggee
   * 2. begins listening for debug clients
   */
  override def run(): Unit = {
    ioScheduler.executeAsync(() => {
      if (isStarted.compareAndSet(false, true)) {
        try {
          // start the debuggee
          val logger = new DebugSessionLogger(this, debugAddress)
          runningDebuggee.set(startDebuggee(logger).runAsync(ioScheduler))

          super.run()
        } finally {
          exitStatusPromise.trySuccess(Terminated)
        }
      }
    })
  }

  override def dispatchRequest(request: Request): Unit = {
    val id = request.seq
    request.command match {
      case "launch" =>
        launches.add(id)
        val _ = Task
          .fromFuture(debugAddress.future)
          .map(DebugSession.toAttachRequest(id, _))
          .foreachL(super.dispatchRequest)
          .runAsync(ioScheduler)

      case "disconnect" =>
        // If deserializing args throws, let `dispatchRequest` reproduce and handle it again
        Try(JsonUtils.fromJson(request.arguments, classOf[DisconnectArguments]))
          .filter(_.restart)
          .foreach(args => exitStatusPromise.trySuccess(Restarted))
        super.dispatchRequest(request)
      case _ => super.dispatchRequest(request)
    }
  }

  override def sendResponse(response: Response): Unit = {
    val requestId = response.request_seq

    response.command match {
      case "attach" if launches(requestId) =>
        // Trick dap4j into thinking we're processing a launch instead of attach
        response.command = Command.LAUNCH.getName
        super.sendResponse(response)
      case "disconnect" if requestId == InternalRequestId =>
        socket.close()
      // don't send the response to the client
      case _ =>
        super.sendResponse(response)
    }
  }

  override def sendEvent(event: Events.DebugEvent): Unit = {
    super.sendEvent(event)

    if (event.`type` == "exited") {
      debuggeeExited.success(())
    }
  }
}

object DebugSession {
  private[DebugSession] val InternalRequestId = Int.MinValue

  trait ExitStatus
  case object Restarted extends ExitStatus
  case object Terminated extends ExitStatus

  def open(
      socket: Socket,
      startDebuggee: DebugSessionLogger => Task[Unit],
      ioScheduler: Scheduler
  ): Task[DebugSession] = {
    for {
      _ <- Task.fromTry(JavaDebugInterface.isAvailable)
    } yield new DebugSession(socket, startDebuggee, ioScheduler)
  }

  private[DebugSession] def toAttachRequest(seq: Int, address: InetSocketAddress): Request = {
    val arguments = new AttachArguments
    arguments.hostName = address.getHostName
    arguments.port = address.getPort

    val json = JsonUtils.toJsonTree(arguments, classOf[AttachArguments])
    new Request(seq, Command.ATTACH.getName, json.getAsJsonObject)
  }

  private[DebugSession] def disconnectRequest(seq: Int): Request = {
    val arguments = new DisconnectArguments
    arguments.restart = false

    val json = JsonUtils.toJsonTree(arguments, classOf[DisconnectArguments])
    new Request(seq, Command.DISCONNECT.getName, json.getAsJsonObject)
  }
}
