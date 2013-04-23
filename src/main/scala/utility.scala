package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{mutable, immutable}

/**
  * Class to handle calling a function only if its input files are newer
  * than the file it will create
  */
class FunctionWithResultPath( val resultPath : File, val fn : () => File )
{
    def apply() = fn()
    def runIfNotCached( stateCacheDir : File, inputDeps : Seq[File] ) =
    {
        val lazyBuild = FileFunction.cached( stateCacheDir / resultPath.toString , FilesInfo.lastModified, FilesInfo.exists ) 
        { _ =>
            Set( fn() )
        }
        lazyBuild(inputDeps.toSet)
        
        resultPath
    }
}

object FunctionWithResultPath
{
    def apply( resultPath : File )( fn : File => Unit ) =
    {
        new FunctionWithResultPath( resultPath, () => { fn(resultPath); resultPath } )
    }
}

class ProcessOutputToString extends ProcessLogger
{
    val stderr = mutable.ArrayBuffer[String]()
    val stdout = mutable.ArrayBuffer[String]()
    
    override def buffer[T]( f : => T ) = f
    override def error( s : => String ) = stderr.append(s)
    override def info( s : => String )  = stdout.append(s)
}

class ProcessOutputToLog( val log : Logger ) extends ProcessOutputToString
{
    override def buffer[T]( f : => T ) = f
    override def error( s : => String ) = { super.error(s); log.error(s) }
    override def info( s : => String )  = { super.info(s); log.info(s) }
}


object ProcessUtil
{
    def captureProcessOutput( pb : ProcessBuilder ) : (Int, String, String) =
    {
        val stderr = mutable.ArrayBuffer[String]()
        val stdout = mutable.ArrayBuffer[String]()
        
        val peos = new ProcessOutputToString
        val res = pb ! peos
        
        (res, peos.stderr.mkString("\n"), peos.stdout.mkString("\n"))
    }
    
    /**
      * Helper function to process sbt process log stdout and stderr streams to
      * a couple of files
      */
    def pipeProcessOutput( pb : ProcessBuilder, stdoutFile : File, stderrFile : File )
    {
        val (res, stderr, stdout) = captureProcessOutput(pb)
        
        IO.write( stderrFile, stderr )
        IO.write( stdoutFile, stdout )
        
        if ( res != 0 )
        {
            sys.error( "Non-zero exit code: " + res.toString )
        }
    }
}


