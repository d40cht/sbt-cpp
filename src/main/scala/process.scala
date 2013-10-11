package org.seacourt.build

import sbt._
import Keys._

import java.io.{File, PrintWriter}

import scala.collection.{ mutable, immutable }

case class AbstractProcess( val name : String, val exe : File, val args : Seq[String], val wd : File, val env : Map[String, String] )
{
    def cmdString = (exe.toString +: args).mkString(" ")
}

case class ProcessExecutionResult( val retCode : Int, val stdout : File, val stderr : File )
{
    def stdoutLines = IO.readLines(stdout)
    def stderrLines = IO.readLines(stderr)
}

trait AbstractRunner
{
    def run( p : AbstractProcess, joinStdOutStdErr : Boolean ) : ProcessExecutionResult
    def run( sp : Seq[AbstractProcess], joinStdOutStdErr : Boolean ) : Seq[ProcessExecutionResult]
}

class LocalRunner extends AbstractRunner
{
    import scala.sys.process._
    
    def run( p : AbstractProcess, joinStdOutStdErr : Boolean ) : ProcessExecutionResult =
    {
        val outputId = java.util.UUID.randomUUID().toString()
        val stdoutFile = p.wd / ( outputId + ".stdout" )
        val stderrFile = p.wd / ( outputId + ".stderr" )
        
        val stdoutPW = new PrintWriter( stdoutFile )
        val stderrPW = new PrintWriter( stderrFile )
        
        def writeToStdout( line : String ) = this.synchronized
        {
            stdoutPW.write( line + "\n" )
        }
        
        def writeToStderr( line : String ) =
        {
            if ( joinStdOutStdErr ) writeToStdout( line )
            else stdoutPW.write( line + "\n" )
        }
        
        val retCode = try
        {
            val pl = ProcessLogger( writeToStdout, writeToStderr )
            Process( p.exe.toString +: p.args, p.wd, p.env.toSeq : _* ) ! pl
        }
        finally
        {
            stderrPW.close
            stdoutPW.close
        }
        
        ProcessExecutionResult( retCode, stdoutFile, stderrFile )
    }
    
    def run( sp : Seq[AbstractProcess], joinStdOutStdErr : Boolean ) : Seq[ProcessExecutionResult] = sp.map( run(_, joinStdOutStdErr) )
}

object ProcessHelper
{
    def runProcess( log : Logger, process : AbstractProcess, mergeToStdout : Boolean, quiet : Boolean ) : ProcessExecutionResult =
    {
        val lr = new LocalRunner()
        log.debug("Executing: " + process.cmdString)
        val res = lr.run( process, true )
        
        if ( quiet )
        {
            IO.readLines( res.stdout ).foreach( log.debug(_) )
        }
        else if ( res.retCode == 0 )
        {
            IO.readLines( res.stdout ).foreach( log.warn(_) )
        }
        else
        {
            IO.readLines( res.stdout ).foreach( log.error(_) )
        }
        
        if ( res.retCode != 0 ) throw new java.lang.RuntimeException("Non-zero exit code: " + res.retCode)
        
        res
    }
}

/*
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
  override def error(s: => String) = if (mergeToStdout) stdoutAppend(s) else stderrAppend(s)
  override def info(s: => String) = stdoutAppend(s)
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

*/
