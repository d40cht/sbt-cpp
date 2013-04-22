package org.seacourt.build

import sbt._
import Keys._
import complete.{Parser, RichParser}
import complete.DefaultParsers._
import scala.collection.{mutable, immutable}

//import sbt.std.{TaskStreams}

/**
  * The base trait from which all native compilers must be inherited in order
  */
trait Compiler
{
    def toolPaths : Seq[File]
    def defaultLibraryPaths : Seq[File]
    def defaultIncludePaths : Seq[File]
    def findHeaderDependencies( s : TaskStreams, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) : FunctionWithResultPath
    def compileToObjectFile( s : TaskStreams, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) : FunctionWithResultPath
    def buildStaticLibrary( s : TaskStreams, buildDirectory : File, libName : String, objectFiles : Seq[File] ) : FunctionWithResultPath
    def buildSharedLibrary( s : TaskStreams, buildDirectory : File, libName : String, objectFiles : Seq[File] ) : FunctionWithResultPath
    def buildExecutable( s : TaskStreams, buildDirectory : File, exeName : String, linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File] ) : FunctionWithResultPath
}

/**
  * Build configurations for a particular project must inherit from this trait.
  * See the default in NativeDefaultBuild for more details
  */
trait BuildTypeTrait
{
    def name        : String
    def pathDirs    : Seq[String]
}

/**
  * Keys for a native build that should be visible from all types of sbt project (including scala)
  */
object NativeBuild
{    
    val exportedLibs = TaskKey[Seq[File]]("native-exported-libs", "All libraries exported by this project" )
    val exportedLibDirectories = TaskKey[Seq[File]]("native-exported-lib-directories", "All library directories exported by this project" )
    val exportedIncludeDirectories = TaskKey[Seq[File]]("native-exported-include-directories", "All include directories exported by this project" )
}

/**
  * The base mechanics, keys and build graph for a native build.
  * The possible build configurations remain abstract via BuildType and
  * the configurations Set. These need to be provided in a derived class.
  */

abstract class NativeBuild extends Build
{
    import NativeBuild._
    
    type SubProjectBuilders = Seq[Project => Project]
    
    private lazy val allProjectVals : Seq[Project] = ReflectUtilities.allVals[Project](this).values.toSeq
    private lazy val nativeProjectVals : Seq[NativeProject] = ReflectUtilities.allVals[NativeProject](this).values.toSeq
    
    def nativeProjects = nativeProjectVals
    
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
    val buildEnvironment = TaskKey[Environment]("native-build-environment")
    val rootBuildDirectory = TaskKey[File]("native-root-build-dir", "Build root directory (for the config, not the project)")
    val projectBuildDirectory = TaskKey[File]("native-project-build-dir", "Build directory for this config and project")
    val stateCacheDirectory = TaskKey[File]("native-state-cache-dir", "Build state cache directory")
    val projectDirectory = TaskKey[File]("native-project-dir", "Project directory")
    val sourceDirectories = TaskKey[Seq[File]]("native-source-dirs", "Source directories")
    val includeDirectories = TaskKey[Seq[File]]("native-include-dirs", "Include directories")
    val systemIncludeDirectories = TaskKey[Seq[File]]("native-system-include-dirs", "System include directories")
    val linkDirectories = TaskKey[Seq[File]]("native-link-dirs", "Link directories")
    val nativeLibraries = TaskKey[Seq[String]]("native-libraries", "All native library dependencies for this project")
    val sourceFiles = TaskKey[Seq[File]]("native-source-files", "All source files for this project")
    val sourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("source-files-with-deps", "All source files for this project")
    val objectFiles = TaskKey[Seq[File]]("native-object-files", "All object files for this project" )
    val nativeExe = TaskKey[File]("native-exe", "Executable built by this project (if appropriate)" )
    val nativeRun = TaskKey[Unit]("native-run", "Perform a native run of this project" )
    val testProject = TaskKey[Project]("native-test-project", "The test sub-project for this project")
    val nativeTest = TaskKey[Option[(File, File)]]("native-test-run", "Run the native test, returning the files with stdout and stderr respectively")
    val test = TaskKey[Unit]("test", "Run the test associated with this project")
    val runEnvironmentVariables = TaskKey[Seq[(String, String)]]("native-run-env-vars", "Environment variables to be set for test runs")
    val testEnvironmentVariables = TaskKey[Seq[(String, String)]]("native-test-env-vars", "Environment variables to be set for test runs")
    val cleanAll = TaskKey[Unit]("native-clean-all", "Clean the entire build directory")
    val cppCompileFlags = TaskKey[Seq[String]]("native-cpp-compile-flags", "C++ compile flags")
    

    type Sett = Project.Setting[_]

    val envKey = AttributeKey[Environment]("envKey")
    
    val buildOptsParser = Space ~> configurations.map( x => token(x.conf.name) ).reduce(_ | _)
    
    
    val nativeBuildConfigurationCommandName = "native-build-configuration"
    
    def setBuildConfigCommand = Command(nativeBuildConfigurationCommandName)(_ => buildOptsParser)
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
                    
                    if ( beo.isEmpty ) sys.error( "Please set a build configuration using the %s command".format(nativeBuildConfigurationCommandName) )
                    
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
                
                sourceDirectories   <<= (projectDirectory) map { pd => Seq(pd / "source") },
                
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
                
                runEnvironmentVariables    := Seq(),
                
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
                
                nativeTest                  :=  None,
                test                        :=  Unit,
                exportedLibs                <<= (streams, name) map { case (s, n) => s.log.warn( "No libraries exported by this project (%s)".format(n) ); Seq() },
                exportedLibDirectories      <<= (streams, name) map { case (s, n) => s.log.warn( "No library paths exported by this project (%s)".format(n) ); Seq() },
                exportedIncludeDirectories  <<= (streams, name) map { case (s, n) => s.log.warn( "No include directories exported by this project (%s)".format(n) ); Seq() },
                nativeExe                   <<= (streams, name) map { case (s, n) => s.log.warn( "No executable built by this project (%s)".format(n) ); file("") }
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
                nativeTest <<= (nativeExe, testEnvironmentVariables, stateCacheDirectory, streams) map
                { case (ncExe, tenvs, scd, s) =>

                    val stdoutFile = file( ncExe + ".stdout" )
                    val stderrFile = file( ncExe + ".stderr" )
                    
                    val tcf = FunctionWithResultPath( stderrFile )
                    { _ =>
                        s.log.info( "Running test: " + ncExe )
                        
                        val pb = Process( ncExe.toString :: Nil, _projectDirectory, tenvs : _* )
                        
                        ProcessUtil.pipeProcessOutput( pb, stdoutFile, stderrFile )
                    }
                    
                    tcf.runIfNotCached( scd, Seq(ncExe) )
                    
                    Some( (stdoutFile, stderrFile) )
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
                    
                    (argTask, runEnvironmentVariables, nativeExe, streams) map
                    { case (args, renvs, ncExe, s) =>
                    
                        val res = Process( ncExe.toString +: args, _projectDirectory, renvs : _* ) !
                    
                        if ( res != 0 ) sys.error( "Non-zero exit code: " + res.toString )
                    }
                }
            )
            NativeProject( _name, _projectDirectory, defaultSettings ++ settings )
        }
    }
}



