package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{ mutable, immutable }

/**
 * Class to handle calling a function only if its input files are newer
 * than the file it will create
 */

class FunctionWithResultPath(val resultPath: File, val fn: () => Unit)
{
  import java.security.MessageDigest

  private def md5(s: String) = MessageDigest.getInstance("MD5").digest(s.getBytes).map("%02X" format _).mkString

  def apply() = { fn(); resultPath }
  def runIfNotCached(stateCacheDir: File, inputDeps: Seq[File]) =
  {
    val resultPathHash = md5(resultPath.toString)
    val lazyBuild = FileFunction.cached( stateCacheDir / resultPathHash, FilesInfo.lastModified, FilesInfo.exists)
    { _ =>
    
      IO.delete( resultPath )
      fn()
      Set(resultPath)
    }
      
    lazyBuild(inputDeps.toSet)

    resultPath
  }
}

object FunctionWithResultPath
{
  def apply(resultPath: File)(fn: File => Unit): FunctionWithResultPath =
  {
    new FunctionWithResultPath( resultPath, () => { fn(resultPath) })
  }
}

class ProcessOutputToString(
  val mergeToStdout: Boolean = false) extends ProcessLogger {
  val stderr = mutable.ArrayBuffer[String]()
  val stdout = mutable.ArrayBuffer[String]()

  def stderrAppend(s: String) = this.synchronized {
    stderr.append(s)
  }

  def stdoutAppend(s: String) = this.synchronized {
    stdout.append(s)
  }

  override def buffer[T](f: => T) = f
  override def error(s: => String) =
    if (mergeToStdout) stdoutAppend(s) else stderrAppend(s)
  override def info(s: => String) = stdoutAppend(s)
}

class ProcessOutputToLog(val log: Logger) extends ProcessOutputToString {
  override def buffer[T](f: => T) = f
  override def error(s: => String) = { super.error(s); log.error(s) }
  override def info(s: => String) = { super.info(s); log.info(s) }
}


object ProcessHelper {
  case class ProcessResult(
    retCode: Int,
    stdout: Seq[String],
    stderr: Seq[String])

  def runProcess(
    log: Logger,
    cmd: Seq[String],
    cwd: File,
    env: Seq[(String, String)],
    quiet: Boolean) = {
    val pl = new ProcessOutputToString(true)

    log.debug("Executing: " + cmd.mkString(" "))
    val res = Process(cmd, cwd, env: _*) ! pl

    if (quiet) {
      pl.stdout.foreach(log.debug(_))
    } else {
      if (res == 0) {
        pl.stdout.foreach(log.warn(_))
      } else {
        pl.stdout.foreach(log.error(_))
      }
    }

    if (res != 0)
      throw new java.lang.RuntimeException("Non-zero exit code: " + res)

    new ProcessResult(res, pl.stdout, pl.stderr)
  }
}


class HeaderConfigFile(private val fileName: File) {
  private val defs = mutable.ArrayBuffer[(String, String)]()

  def addDefinition(name: String) = defs.append((name, ""))
  def addDefinition(name: String, value: String) = defs.append((name, value))

  def write() = {
    IO.write(fileName, defs.map {
      case (k, v) => "#define %s %s".format(k, v)
    }.mkString("\n"))
  }
}

object HeaderConfigFile {
  def apply(log: Logger, compiler: Compiler, fileName: File)( fn: HeaderConfigFile => Unit): File =
  {
      FunctionWithResultPath(fileName)
      { _ =>
        val hcf = new HeaderConfigFile(fileName)

        fn(hcf)

        hcf.write()
      }()
    }
}

