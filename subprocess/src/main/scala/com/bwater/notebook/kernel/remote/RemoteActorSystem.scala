/*
 * Copyright (c) 2013  Bridgewater Associates, LP
 *
 * Distributed under the terms of the Modified BSD License.  The full license is in
 * the file COPYING, distributed as part of this software.
 */
package com.bwater.notebook.kernel.remote

import com.bwater.notebook.kernel.pfork.{ProcessInfo, BetterFork, ForkableProcess}
import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.remote.{RemoteScope, RemoteActorRefProvider}
import akka.dispatch.Future
import java.io.File
import org.apache.commons.io.FileUtils
import java.util.concurrent.atomic.AtomicInteger

/**
 * Author: Ken
 */
class RemoteActorProcess extends ForkableProcess{
  // http://stackoverflow.com/questions/14995834/programmatically-obtain-ephemeral-port-with-akka
  var _system: ActorSystem = null

  def init(args: Seq[String]): String = {
    val configFile = args(0)
    val cfg = ConfigFactory.load(configFile)

    // Cookie file is optional second argument
    val actualCfg = args match {
      case Seq(_, cookieFile) if (cookieFile.size > 0) =>
        val cookie = FileUtils.readFileToString(new File(cookieFile))
        AkkaConfigUtils.requireCookie(cfg, cookie)
      case _ => cfg
    }

    _system = ActorSystem("Remote", actualCfg)

    val address = GetAddress(_system).address
    println(address)
    address.toString
//    address.port.get?OrElse(sys.error("not a remote actor system: %s".format(cfg))).toString
  }

  def waitForExit() {
    _system.awaitTermination()
  }
}

class FindAddressImpl(system: ExtendedActorSystem) extends Extension {
  def address = system.provider match {
    case rarp: RemoteActorRefProvider => rarp.transport.address
    case _ => system.provider.rootPath.address
  }
}

object GetAddress extends ExtensionKey[FindAddressImpl]
case object RemoteShutdown

class ShutdownActor extends Actor {
  protected def receive = {
    case RemoteShutdown => context.system.shutdown()
  }

}

/**
 * Represents a running remote actor system, with an address and the ability to kill it
 */
class RemoteActorSystem(localSystem: ActorSystem, info: ProcessInfo) {
  val address = AddressFromURIString(info.initReturn)
  val shutdownActor = localSystem.actorOf(Props(new ShutdownActor).withDeploy(Deploy(scope = RemoteScope(address))))

  def actorOf(context: ActorRefFactory, props: Props) = context.actorOf(props.withDeploy(Deploy(scope = RemoteScope(address))))

  def deploy = Deploy(scope = RemoteScope(address))

  def shutdownRemote() { shutdownActor ! RemoteShutdown }
  def killRemote() { info.kill() }

}

/**
 * Create a remote actor system
 */
object RemoteActorSystem {
  val nextId = new AtomicInteger(1)
  def spawn(system: ActorSystem, configFile:String): Future[RemoteActorSystem] = {
    val cookiePath = AkkaConfigUtils.requiredCookie(system.settings.config) match {
      case Some(cookie) =>
        val cookieFile = new File(".", ".akka-cookie")
        FileUtils.writeStringToFile(cookieFile, cookie)
        cookieFile.getAbsolutePath
      case _ => ""
    }
    new BetterFork[RemoteActorProcess](system.dispatcher).execute(configFile, cookiePath) map { new RemoteActorSystem(system, _) }
  }
}
