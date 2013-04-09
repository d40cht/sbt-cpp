package org.seacourt.build

import sbt._
import Keys._
import complete.{Parser, RichParser}
import complete.DefaultParsers._
import scala.collection.{mutable, immutable}

import sbt.std.{TaskStreams}

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

trait Compiler
{
    def findHeaderDependencies( s : TaskStreams[_], buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) : FunctionWithResultPath
    def compileToObjectFile( s : TaskStreams[_], buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) : FunctionWithResultPath
    def buildStaticLibrary( s : TaskStreams[_], buildDirectory : File, libName : String, objectFiles : Seq[File] ) : FunctionWithResultPath
    def buildSharedLibrary( s : TaskStreams[_], buildDirectory : File, libName : String, objectFiles : Seq[File] ) : FunctionWithResultPath
    def buildExecutable( s : TaskStreams[_], buildDirectory : File, exeName : String, linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File] ) : FunctionWithResultPath
}

trait BuildTypeTrait
{
    def name        : String
    def pathDirs    : Seq[String]
}



case class GccCompiler(
    val compilerPath : File,
    val archiverPath : File,
    val linkerPath : File,
    val compileFlags : String = "",
    val linkFlags : String = "" ) extends Compiler
{
    def findHeaderDependencies( s : TaskStreams[_], buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".d") )
    { depFile =>
    
        val includePathArg = includePaths.map( ip => "-I " + ip ).mkString(" ")
        val systemIncludePathArg = systemIncludePaths.map( ip => "-isystem " + ip ).mkString(" ")
        val additionalFlags = compilerFlags.mkString(" ")
        val depCmd = "%s %s %s -M %s %s %s".format( compilerPath, compileFlags, additionalFlags, includePathArg, systemIncludePathArg, sourceFile )
        s.log.info( "Executing: " + depCmd )
        val depResult = stringToProcess( depCmd ).lines
        
        // Strip off any trailing backslash characters from the output
        val depFileLines = depResult.map( _.replace( "\\", "" ) )
    
        // Drop the first column and split on spaces to get all the files (potentially several per line )
        val allFiles = depFileLines.flatMap( _.split(" ").drop(1) ).map( x => new File(x.trim) )
        
        IO.write( depFile, allFiles.mkString("\n") )
    }
    
    def compileToObjectFile( s : TaskStreams[_], buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".o") )
    { outputFile =>
    
        val includePathArg = includePaths.map( ip => "-I " + ip ).mkString(" ")
        val systemIncludePathArg = systemIncludePaths.map( ip => "-isystem " + ip ).mkString(" ")
        val additionalFlags = compilerFlags.mkString(" ")
        val buildCmd = "%s -fPIC %s %s %s %s -c -o %s %s".format( compilerPath, compileFlags, additionalFlags, includePathArg, systemIncludePathArg, outputFile, sourceFile )
                       
        s.log.info( "Executing: " + buildCmd )
        buildCmd !!
    }
    
    def buildStaticLibrary( s : TaskStreams[_], buildDirectory : File, libName : String, objectFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".a") )
        { outputFile =>
        
            val arCmd = "%s -c -r %s %s".format( archiverPath, outputFile, objectFiles.mkString(" ") )
            s.log.info( "Executing: " + arCmd )
            arCmd !!
        }
        
    def buildSharedLibrary( s : TaskStreams[_], buildDirectory : File, libName : String, objectFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".so") )
        { outputFile =>
        
            val cmd = "%s -shared -o %s %s".format( compilerPath, outputFile, objectFiles.mkString(" ") )
            s.log.info( "Executing: " + cmd )
            cmd !!
        }
        
    def buildExecutable( s : TaskStreams[_], buildDirectory : File, exeName : String, linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / exeName )
        { outputFile =>
        
            val linkPathArg = linkPaths.map( lp => "-L " + lp ).mkString(" ")
            val libArgs = linkLibraries.map( ll => "-l" + ll ).mkString(" ")
            val linkCmd = "%s %s -o %s %s %s %s".format( linkerPath, linkFlags, outputFile, inputFiles.mkString(" "), linkPathArg, libArgs )
            s.log.info( "Executing: " + linkCmd )
            linkCmd !!
        }
}

object NativeBuild
{    
    val exportedLibs = TaskKey[Seq[File]]("exported-libs", "All libraries exported by this project" )
    val exportedLibDirectories = TaskKey[Seq[File]]("exported-lib-directories", "All library directories exported by this project" )
    val exportedIncludeDirectories = TaskKey[Seq[File]]("exported-include-directories", "All include directories exported by this project" )
}


abstract class NativeBuild extends Build
{
    import NativeBuild._
    
    type SubProjectBuilders = Seq[Project => Project]
    
    private lazy val allProjectVals : Seq[Project] = ReflectUtilities.allVals[Project](this).values.toSeq
    private lazy val nativeProjectVals : Seq[NativeProject] = ReflectUtilities.allVals[NativeProject](this).values.toSeq
    
    override lazy val projects : Seq[Project] =
    {
        // Extract and register any subprojects within the native projects (Generally used for test purposes)
        val nativeSubProjects : Seq[Project] = nativeProjectVals.flatMap( np => np.subProjectBuilders.map( _(np.p) ) )
        
        val allProjects = allProjectVals ++ nativeProjectVals.map( _.p ) ++ nativeSubProjects
        val allProjectRefs = allProjects.map( p => LocalProject(p.id) )
        
        // If there isn't an 'all' project, make one
        if ( allProjects.map( _.id ) contains "all" )
        {
            allProjects
        }
        else
        {
            NativeProject("all", file("."), Seq()).aggregate( allProjectRefs : _* ) +: allProjects
        }
    }

    type BuildType <: BuildTypeTrait
    
    case class Environment( val conf : BuildType, val compiler : Compiler )
    
    def configurations : Set[Environment]
    
    val compiler = TaskKey[Compiler]("native-compiler")
    val buildEnvironment = TaskKey[Environment]("build-environment")
    val rootBuildDirectory = TaskKey[File]("root-build-dir", "Build root directory (for the config, not the project)")
    val projectBuildDirectory = TaskKey[File]("project-build-dir", "Build directory for this config and project")
    val stateCacheDirectory = TaskKey[File]("state-cache-dir", "Build state cache directory")
    val projectDirectory = TaskKey[File]("project-dir", "Project directory")
    val sourceDirectories = TaskKey[Seq[File]]("source-dir", "Source directory")
    val includeDirectories = TaskKey[Seq[File]]("include-dirs", "Include directories")
    val systemIncludeDirectories = TaskKey[Seq[File]]("system-include-dirs", "System include directories")
    val linkDirectories = TaskKey[Seq[File]]("link-dirs", "Link directories")
    val nativeLibraries = TaskKey[Seq[String]]("native-libraries", "All native library dependencies for this project")
    val sourceFiles = TaskKey[Seq[File]]("source-files", "All source files for this project")
    val sourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("source-files-with-deps", "All source files for this project")
    val objectFiles = TaskKey[Seq[File]]("object-files", "All object files for this project" )
    val objectFiles2 = TaskKey[Seq[File]]("object-files", "All object files for this project" )
    val nativeExe = TaskKey[File]("native-exe", "Executable built by this project (if appropriate)" )
    val nativeRun = TaskKey[Unit]("native-run", "Perform a native run of this project" )
    val testProject = TaskKey[Project]("test-project", "The test sub-project for this project")
    val nativeTest = TaskKey[(File, File)]("native-test-run", "Run the native test, returning the files with stdout and stderr respectively")
    val test = TaskKey[Unit]("test", "Run the test associated with this project")
    val testEnvironmentVariables = TaskKey[Seq[(String, String)]]("test-env-vars", "Environment variables to be set for test runs")
    val cleanAll = TaskKey[Unit]("clean-all", "Clean the entire build directory")
    val cppCompileFlags = TaskKey[Seq[String]]("cpp-compile-flags", "C++ compile flags")
    

    type Sett = Project.Setting[_]

    val envKey = AttributeKey[Environment]("envKey")
    
    val buildOptsParser = Space ~> configurations.map( x => token(x.conf.name) ).reduce(_ | _)
    
    def setBuildConfigCommand = Command("build-environment")(_ => buildOptsParser)
    {
        (state, envName) =>
   
        val envDict = configurations.map( x => (x.conf.name, x) ).toMap
        val env = envDict(envName)

        val updatedAttributes = state.attributes.put( envKey, env )
        
        state.copy( attributes=updatedAttributes )
    }
    
    class NativeProject( val p : Project, val subProjectBuilders : SubProjectBuilders )
    {
        def nativeDependsOn( others : ProjectReference* ) : NativeProject =
        {
            val newP = others.foldLeft(p)
            { case (np, other) =>
                np.dependsOn( other ).settings(
                    includeDirectories  <++= (exportedIncludeDirectories in other),
                    objectFiles         <++= (exportedLibs in other),
                    compile             <<=  compile.dependsOn(exportedLibs in other) )
            }
            
            new NativeProject( newP, subProjectBuilders )
        }
        
        def nativeSystemDependsOn( others : ProjectReference* ) : NativeProject =
        {
            val newP = others.foldLeft(p)
            { case (np, other) =>
                np.dependsOn( other ).settings(
                    systemIncludeDirectories    <++= (exportedIncludeDirectories in other),
                    objectFiles                 <++= (exportedLibs in other),
                    compile                     <<=  compile.dependsOn(exportedLibs in other) )
            }
            
            new NativeProject( newP, subProjectBuilders )
        }
    }
    
    implicit def toProject( rnp : NativeProject ) = rnp.p
    implicit def toProjectRef( rnp : NativeProject ) : ProjectReference = LocalProject(rnp.p.id)
    
    
    object NativeProject
    {
        // A selection of useful default settings from the standard sbt config
        lazy val relevantSbtDefaultSettings = Seq[Sett](
            watchTransitiveSources  <<= Defaults.watchTransitiveSourcesTask,                
            watch                   <<= Defaults.watchSetting
        )
    
        private def taskMapReduce[T, S]( inputs : Seq[T] )( mapFn : T => S )( reduceFn : (S, S) => S ) : Task[S] =
        {
            val taskSeq : Seq[Task[S]] = inputs.map( i => toTask( () => mapFn(i) ) ).toIndexedSeq
            
            val reduceRes : Task[S] = sbt.std.TaskExtra.reduced( taskSeq.toIndexedSeq, reduceFn )
            
            reduceRes
        }
        
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]], subProjectBuilders : SubProjectBuilders = Seq() ) =
        {
            val defaultSettings = relevantSbtDefaultSettings ++ Seq(
                name                := _name,
                
                projectDirectory    <<= baseDirectory map { bd => (bd / _projectDirectory.toString) },
            
                commands            ++= (BasicCommands.allBasicCommands :+ setBuildConfigCommand),
                
                buildEnvironment    <<= state map
                { s =>
                    val beo = s.attributes.get( envKey )
                    if ( beo.isEmpty ) sys.error( "Please set a build configuration using the build-environment command" )
                    
                    beo.get
                },
                
                target              <<= baseDirectory { _ / "target" / "native" },
                
                historyPath         <<= target { t => Some(t / ".history") },
                
                rootBuildDirectory  <<= (target, buildEnvironment) map
                { case (td, be) =>
                
                    val dir = be.conf.pathDirs.foldLeft( td )( _ / _ )
                    
                    IO.createDirectory(dir)
                    
                    dir
                },
                
                clean               <<= (rootBuildDirectory) map { rbd => IO.delete(rbd) },
                
                cleanAll            <<= (target) map { td => IO.delete(td) },
                
                compiler            <<= (buildEnvironment) map { _.compiler },
                
                projectBuildDirectory <<= (rootBuildDirectory, name) map
                { case (rbd, n) =>
                
                    val dir = rbd / n
                    
                    IO.createDirectory(dir)
                    
                    dir
                },
                
                stateCacheDirectory <<= (projectBuildDirectory) map { _ / "state-cache"  },
                
                includeDirectories  <<= (projectDirectory) map { pd => Seq(pd / "interface", pd / "include") },
                
                systemIncludeDirectories := Seq(),
                
                linkDirectories     :=  Seq(),
                
                nativeLibraries     <<= (projectBuildDirectory) map { _ => Seq() },
                
                sourceDirectories  <<= (projectDirectory) map { pd => Seq(pd / "source") },
                
                sourceFiles         <<= (sourceDirectories) map { _.flatMap { sd => ((sd * "*.cpp").get ++ (sd * "*.c").get) } },
                
                cppCompileFlags     := Seq(),
                
                sourceFilesWithDeps <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, sourceFiles, cppCompileFlags, streams) flatMap
                {
                    case (c, bd, scd, ids, sids, sfs, cfs, s) =>
                    
                    // Calculate dependencies
                    def findDependencies( sourceFile : File ) : Seq[File] =
                    {
                        val depGen = c.findHeaderDependencies( s, bd, ids, sids, sourceFile, cfs )
                        
                        depGen.runIfNotCached( scd, Seq(sourceFile) )
                        
                        IO.readLines(depGen.resultPath).map( file )
                    }
                    
                    taskMapReduce( sfs ) { sf => Seq( (sf, findDependencies(sf)) ) }( _ ++ _ )
                    //sfs.par.map( sf => (sf, findDependencies(sf) ) ).seq
                },
                
                testEnvironmentVariables    := Seq(),
                
                watchSources        <++= (sourceFilesWithDeps) map { sfd => sfd.toList.flatMap { case (sf, deps) => (sf +: deps.toList) } },
                
                objectFiles         <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, sourceFilesWithDeps, cppCompileFlags, streams) flatMap {
                    
                    case (c, bd, scd, ids, sids, sfwdeps, cfs, s) =>
                    
                    taskMapReduce( sfwdeps )
                    { case (sourceFile, dependencies) =>
                    
                        val blf = c.compileToObjectFile( s, bd, ids, sids, sourceFile, cfs )
                        
                        Seq(blf.runIfNotCached( scd, dependencies ))
                    }( _ ++ _ )
                },
                
                test                        <<= (streams, name) map { case (s, n) => s.log.info( "No tests defined for this project (%s)".format(n) ) },
                exportedLibs                <<= (streams, name) map { case (s, n) => s.log.info( "No libraries exported by this project (%s)".format(n) ); Seq() },
                exportedLibDirectories      <<= (streams, name) map { case (s, n) => s.log.info( "No library paths exported by this project (%s)".format(n) ); Seq() },
                exportedIncludeDirectories  <<= (streams, name) map { case (s, n) => s.log.info( "No include directories exported by this project (%s)".format(n) ); Seq() },
                nativeExe                   <<= (streams, name) map { case (s, n) => s.log.info( "No executable built by this project (%s)".format(n) ); file("") }
            )
            
            val p = Project( id=_name, base=file("./"), settings=defaultSettings ++ _settings )
            
            new NativeProject(p, subProjectBuilders)
        }
    }

    object Library
    {
        private def buildLib( _name : String, _projectDirectory : File, settings : => Seq[sbt.Project.Setting[_]], isShared : Boolean, subProjectBuilders : SubProjectBuilders = Seq() ) =
        {
            val defaultSettings = Seq(
                exportedLibs <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, streams) map
                { case (c, projName, bd, scd, ofs, s) =>
                
                    val blf = if ( isShared ) c.buildSharedLibrary( s, bd, projName, ofs )
                    else c.buildStaticLibrary( s, bd, projName, ofs )
                    
                    Seq( blf.runIfNotCached( scd, ofs ) )
                },
                exportedIncludeDirectories  <<= (projectDirectory) map { pd => Seq(pd / "interface") },
                exportedLibDirectories      <<= exportedLibs map { _.map( _.getParentFile ).distinct },
                compile                     <<= exportedLibs map { nc => sbt.inc.Analysis.Empty }
                
                
            )
            NativeProject( _name, _projectDirectory, defaultSettings ++ settings, subProjectBuilders )
        }
        
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]], isShared : Boolean, ignoreTestDir : Boolean = false ) =
        {
            val testDir = (_projectDirectory / "test")
            
            val subProjectBuilders : SubProjectBuilders = if ( !ignoreTestDir && testDir.exists )
            {
                val testName = _name + "_test"
                
                def buildTestProject( mainLibrary : Project ) =
                {
                    NativeTest( testName, testDir, Seq
                    (
                        systemIncludeDirectories    <++= (systemIncludeDirectories in mainLibrary),
                        includeDirectories          <++= (includeDirectories in mainLibrary),
                        objectFiles                 <++= /*(exportedLibs in mainLibrary) ++*/ (objectFiles in mainLibrary)
                    ) ++ _settings )
                }
                
                Seq( buildTestProject )
            }
            else Seq()
            
            val mainLibrary = buildLib( _name, _projectDirectory, _settings, isShared, subProjectBuilders )
            
            mainLibrary
        }
    }
    
    object StaticLibrary
    {
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]], ignoreTestDir : Boolean = false ) =
            Library(_name, _projectDirectory, _settings, false, ignoreTestDir )
    }
    
    object SharedLibrary
    {
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]], ignoreTestDir : Boolean = false ) =
            Library(_name, _projectDirectory, _settings, true, ignoreTestDir )
    }
    
    
    object NativeTest
    {
        def apply( _name : String, _projectDirectory : File, settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val defaultSettings = Seq(
                nativeExe <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, linkDirectories, nativeLibraries, streams) map
                { case (c, projName, bd, scd, ofs, lds, nls, s) =>
                
                    val blf = c.buildExecutable( s, bd, projName, lds, nls, ofs )
                    
                    blf.runIfNotCached( scd, ofs )
                },
                (compile in Test) <<= nativeExe map { nc => sbt.inc.Analysis.Empty },
                nativeTest <<= (nativeExe, testEnvironmentVariables, streams) map
                { case (ncExe, tenvs, s) =>
                
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
                            s.log.info( stdout )
                            sys.error( "Non-zero exit code: " + res.toString )
                        }
                    }
                    
                    val pb = Process( ncExe.toString :: Nil, _projectDirectory, tenvs : _* )
                    val stdoutFile = file( ncExe + ".stdout" )
                    val stderrFile = file( ncExe + ".stderr" )
                    pipeProcessOutput( pb, stdoutFile, stderrFile )
                    
                    (stdoutFile, stderrFile)
                },
                test <<= nativeTest.map { nt => Unit }
            )
            NativeProject( _name, _projectDirectory, defaultSettings ++ settings )
        }
    }

    object NativeExecutable
    {
        def apply( _name : String, _projectDirectory : File, settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val defaultSettings = Seq(
                nativeExe <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, linkDirectories, nativeLibraries, streams) map
                { case (c, projName, bd, scd, ofs, lds, nls, s) =>
                
                    val blf = c.buildExecutable( s, bd, projName, lds, nls, ofs )
                    
                    blf.runIfNotCached( scd, ofs )
                },
                compile <<= nativeExe map { nc => sbt.inc.Analysis.Empty },
                run <<= inputTask { (argTask: TaskKey[Seq[String]]) =>
                    
                    (argTask, nativeExe, streams) map
                    { case (args, nbExe, s) =>
                    
                        val res = ( nbExe.toString + " " + args.mkString(" ") ) !
                    
                        if ( res != 0 ) sys.error( "Non-zero exit code: " + res.toString )
                    }
                }
            )
            NativeProject( _name, _projectDirectory, defaultSettings ++ settings )
        }
    }
}

object NativeDefaultBuild
{
    sealed trait DebugOptLevel
    case object Release extends DebugOptLevel
    case object Debug   extends DebugOptLevel
    
    sealed trait NativeCompiler    
    case object Gcc     extends NativeCompiler
    case object Clang   extends NativeCompiler
    
    sealed trait TargetPlatform
    case object LinuxPC     extends TargetPlatform
    case object BeagleBone  extends TargetPlatform
}

class NativeDefaultBuild extends NativeBuild
{
    import NativeDefaultBuild._ 
    
    case class BuildType( debugOptLevel : DebugOptLevel, compiler : NativeCompiler, targetPlatform : TargetPlatform ) extends BuildTypeTrait
    {
        def name        = debugOptLevel.toString + "_" + compiler.toString + "_" + targetPlatform.toString
        def pathDirs    = Seq( debugOptLevel.toString, compiler.toString, targetPlatform.toString )
    }
    
    lazy val baseGcc = new GccCompiler( file("/usr/bin/g++-4.7"), file("/usr/bin/ar"), file("/usr/bin/g++-4.7") )
    
    override lazy val configurations = Set[Environment](
        new Environment( new BuildType( Release, Gcc, LinuxPC ), baseGcc.copy( compileFlags="-std=c++11 -O2 -Wall -Wextra -DLINUX -DRELEASE" ) ),
        new Environment( new BuildType( Debug, Gcc, LinuxPC ), baseGcc.copy( compileFlags="-std=c++11 -g -Wall -Wextra -DLINUX -DDEBUG" ) )
    )
}


