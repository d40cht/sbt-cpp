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


object ProcessUtil
{
    def captureProcessOutput( pb : ProcessBuilder ) : (Int, String, String) =
    {
        var stderr = mutable.ArrayBuffer[String]()
        var stdout = mutable.ArrayBuffer[String]()
        class ProcessOutputToString extends ProcessLogger
        {
            override def buffer[T]( f : => T ) = f
            override def error( s : => String ) = stderr.append(s)
            override def info( s : => String )  = stdout.append(s)
        }
        
        val res = pb ! new ProcessOutputToString()
        
        (res, stderr.mkString("\n"), stdout.mkString("\n"))
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


