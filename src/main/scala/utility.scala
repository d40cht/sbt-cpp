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
    import java.security.MessageDigest

    private def md5(s: String) = MessageDigest.getInstance("MD5").digest(s.getBytes).map( "%02X" format _ ).mkString

    def apply() = fn()
    def runIfNotCached( stateCacheDir : File, inputDeps : Seq[File] ) =
    {
        val resultPathHash = md5(resultPath.toString)
        val lazyBuild = FileFunction.cached( stateCacheDir / resultPathHash, FilesInfo.lastModified, FilesInfo.exists ) 
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

class ProcessOutputToString( val mergeToStdout : Boolean = false ) extends ProcessLogger
{
    val stderr = mutable.ArrayBuffer[String]()
    val stdout = mutable.ArrayBuffer[String]()
    
    override def buffer[T]( f : => T ) = f
    override def error( s : => String ) = if (mergeToStdout) stdout.append(s) else stderr.append(s)
    override def info( s : => String )  = stdout.append(s)
}

class ProcessOutputToLog( val log : Logger ) extends ProcessOutputToString
{
    override def buffer[T]( f : => T ) = f
    override def error( s : => String ) = { super.error(s); log.error(s) }
    override def info( s : => String )  = { super.info(s); log.info(s) }
}


class HeaderConfigFile( private val fileName : File )
{
    private val defs = mutable.ArrayBuffer[(String, String)]()
    
    def addDefinition( name : String ) = defs.append( (name, "") )
    def addDefinition( name : String, value : String ) = defs.append( (name, value) )
    
    def write() =
    {
        IO.write( fileName, defs.map { case (k, v) => "#define %s %s".format(k, v) }.mkString("\n") )
    }
}

object HeaderConfigFile
{
    def apply( log : Logger, compiler : Compiler, fileName : File )( fn : HeaderConfigFile => Unit ) =
    {
        FunctionWithResultPath( fileName )
        { _ =>
            val hcf = new HeaderConfigFile( fileName )
            
            fn( hcf )
            
            
            
            hcf.write()
            
            fileName
        }()
    }
}

