package org.seacourt.build

import sbt._
import Keys._

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
    /**
      * Helper function to process sbt process log stdout and stderr streams to
      * a couple of files
      */
    def pipeProcessOutput( pb : ProcessBuilder, stdoutFile : File, stderrFile : File )
    {
        var stderr = ""
        var stdout = ""
        class ProcessOutputToFile extends ProcessLogger
        {
            override def buffer[T]( f : => T ) = f
            override def error( s : => String ) = stderr += s
            override def info( s : => String ) = stdout += s
        }
        
        val res = pb ! new ProcessOutputToFile()
        
        IO.write( stderrFile, stderr )
        IO.write( stdoutFile, stdout )
        
        if ( res != 0 )
        {
            sys.error( "Non-zero exit code: " + res.toString )
        }
    }
}


