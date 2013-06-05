/*
 * Copyright 2011 PEAK6 Investments, L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package peak6.util

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.common.keyprovider.AbstractKeyPairProvider
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.{PasswordAuthenticator, Command}
import org.apache.sshd.common.Factory
import scala.reflect.Manifest
import scala.concurrent.ops.spawn

class ScalaSshShell(val port: Int, val name: String,
                    val user: String, val passwd: String,
                    val keysResourcePath: Option[String]) extends Shell {
  lazy val auth =
    new PasswordAuthenticator {
      def authenticate(u: String, p: String, s: ServerSession) =
        u == user && p == passwd
    }
}

trait Shell {
  def port: Int

  def name: String

  def keysResourcePath: Option[String]

  def auth: PasswordAuthenticator

  var bindings: Seq[(String, String, Any)] = IndexedSeq()

  def bind[T: Manifest](name: String, value: T) {
    bindings :+=(name, manifest[T].toString, value)
  }

  lazy val sshd = {
    val x = org.apache.sshd.SshServer.setUpDefaultServer()
    x.setPort(port)
    x.setReuseAddress(true)
    x.setPasswordAuthenticator(auth)
    x.setKeyPairProvider(keyPairProvider)
    x.setShellFactory(new ShellFactory)
    x
  }

  lazy val keyPairProvider =
    keysResourcePath.map {
      case krp =>
        // 'private' is one of the most annoying things ever invented.
        // Apache's sshd will only generate a key, or read it from an
        // absolute path (via a string, eg can't work directly on
        // resources), but they do privide protected methods for reading
        // from a stream, but not into the internal copy that gets
        // returned when you call loadKey(), which is of course privite
        // so there is no way to copy it. So we construct one provider
        // so we can parse the resource, and then impliment our own
        // instance of another so we can return it from loadKey(). What
        // a complete waste of time.
        new AbstractKeyPairProvider {
          val pair = new SimpleGeneratorHostKeyProvider() {
            val in = classOf[ScalaSshShell].getResourceAsStream(krp)
            val get = doReadKeyPair(in)
          }.get

          override def getKeyTypes() = getKeyType(pair)

          override def loadKey(s: String) = pair

          def loadKeys() = Array[java.security.KeyPair]()
        }
    }.getOrElse(new SimpleGeneratorHostKeyProvider())

  class ShellFactory extends Factory[Command] {
    def create() =
      new org.apache.sshd.server.Command {
        // logger.info("Instantiated")
        var in: java.io.InputStream = null
        var out: java.io.OutputStream = null
        var err: java.io.OutputStream = null
        var exit: org.apache.sshd.server.ExitCallback = null
        var thread: Thread = null
        @volatile var inShutdown = false

        def setInputStream(in: java.io.InputStream) {
          this.in = in
        }

        def setOutputStream(out: java.io.OutputStream) {
          this.out = new java.io.OutputStream {
            override def close() {
              out.close()
            }

            override def flush() {
              out.flush()
            }

            override def write(b: Int) {
              if (b.toChar == '\n')
                out.write('\r')
              out.write(b)
            }

            override def write(b: Array[Byte]) {
              var i = 0
              while (i < b.size) {
                write(b(i))
                i += 1
              }
            }

            override def write(b: Array[Byte], off: Int, len: Int) {
              write(b.slice(off, off + len))
            }
          }
        }

        def setErrorStream(err: java.io.OutputStream) {
          this.err = err
        }

        def setExitCallback(exit: org.apache.sshd.server.ExitCallback) {
          this.exit = exit
        }

        def start(env: org.apache.sshd.server.Environment) {
          thread = CrashingThread.start(Some("ScalaSshShell-" + name)) {
            val pw = new PrintWriter(out)
            // logger.info("New ssh client connected")
            pw.write("Connected to %s, starting repl...\n".format(name))
            pw.flush()

            val il = new scala.tools.nsc.interpreter.SshILoop(None, pw)
            il.setPrompt(name + "> ")
            il.settings = new scala.tools.nsc.Settings()
            il.settings.embeddedDefaults(getClass.getClassLoader)
            il.settings.usejavacp.value = true
            il.createInterpreter()

            il.in = new scala.tools.nsc.interpreter.JLineIOReader(
              in,
              out,
              new scala.tools.nsc.interpreter.JLineCompletion(il.intp))

            if (il.intp.reporter.hasErrors) {
              // logger.error("Got errors, abandoning connection")
              return
            }

            il.printWelcome()
            try {
              il.intp.initialize()
              /*
              il.intp.beQuietDuring {
                il.intp.bind("stdout", pw)
                for ((bname, btype, bval) <- bindings)
                  il.bind(bname, btype, bval)
              }
              il.intp.quietRun(
                """def println(a: Any) = {
                  stdout.write(a.toString)
                stdout.write('\n')
                }""")
              il.intp.quietRun(
                """def exit = println("Use ctrl-D to exit shell.")""")
              */

              il.loop()
            } finally il.closeInterpreter()

            // logger.info("Exited repl, closing ssh.")
            pw.write("Bye.\r\n")
            pw.flush()
            exit.onExit(0)
          }
        }

        def destroy() {
          inShutdown = true
        }
      }
  }

  def start() {
    sshd.start()
  }

  def stop() {
    sshd.stop()
  }
}

object ScalaSshShell {
  def main(args: Array[String]) {
    val sshd = new ScalaSshShell(port = 4444, name = "test", user = "user",
      passwd = "fluke",
      keysResourcePath = Some("/test.ssh.keys"))
    sshd.bind("pi", 3.1415926)
    sshd.bind("nums", Vector(1, 2, 3, 4, 5))
    spawn {
      sshd.start()
    }
    //new java.util.Scanner(System.in) nextLine()
    Thread.sleep(60000)
    sshd.stop()
  }

  def generateKeys(path: String) {
    val key = new SimpleGeneratorHostKeyProvider(path)
    key.loadKeys()
  }
}
