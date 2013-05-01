package org.seacourt.build

import sbt._
import Keys._
import complete.{Parser, RichParser}
import complete.DefaultParsers._
import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
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
    def ccDefaultFlags : Seq[String]
    def cxxDefaultFlags : Seq[String]
    def linkDefaultFlags : Seq[String]
    
    def findHeaderDependencies( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean = false ) : FunctionWithResultPath
    
    def ccCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean = false ) : FunctionWithResultPath
    def cxxCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean = false ) : FunctionWithResultPath

    def buildStaticLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], quiet : Boolean = false ) : FunctionWithResultPath
    def buildSharedLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], quiet : Boolean = false ) : FunctionWithResultPath
    def buildExecutable( log : Logger, buildDirectory : File, exeName : String, linkFlags : Seq[String], linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File], quiet : Boolean = false ) : FunctionWithResultPath
    
    
    protected def reportFileGenerated( log : Logger, genFile : File, quiet : Boolean )
    {
        if ( !quiet ) log.success( genFile.toString )
    }
    
    case class ProcessResult( val retCode : Int, val stdout : String, val stderr : String )
    
    protected def runProcess( log : Logger, cmd : Seq[String], cwd : File, env : Seq[(String, String)], quiet : Boolean ) =
    {
        val pl = new ProcessOutputToString(true)
        
        log.debug( "Executing: " + cmd.mkString(" ") )
        val res = Process( cmd, cwd, env : _* ) ! pl
        
        if ( quiet )
        {
            pl.stdout.foreach( ll => log.debug(ll) )
        }
        else
        {
            if ( res == 0 )
            {
                pl.stdout.foreach( ll => log.info(ll) )
            }
            else
            {
                pl.stdout.foreach( ll => log.error(ll) )
            }
            
        }
        
        if ( res != 0 ) throw new java.lang.RuntimeException( "Non-zero exit code: " + res )
        
        new ProcessResult(res, pl.stdout.mkString("\n"), pl.stderr.mkString("\n"))
    }
}

/**
  * Build configurations for a particular project must inherit from this trait.
  * See the default in NativeDefaultBuild for more details
  */
trait BuildTypeTrait
{
    def name        : String
    def pathDirs    : Seq[String]
    
    def targetDirectory( rootDirectory : File ) = pathDirs.foldLeft( rootDirectory )( _ / _ )
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
    
    // Magic Gav suggested that the ConfigFactory needs the classloader for this class
    // in order to be able to get things out of the jar in which it is packaged. This seems
    // to work, so kudos to him.
    lazy val parseOptions = ConfigParseOptions.defaults().setAllowMissing(true)
    lazy val defaultConf = ConfigFactory.load(getClass.getClassLoader)
    lazy val localConf = ConfigFactory.parseFile( file("build.conf").getAbsoluteFile, parseOptions )
    lazy val userConf = ConfigFactory.parseFile( file("user.conf").getAbsoluteFile, parseOptions )
    
    lazy val conf = userConf.withFallback( localConf ).withFallback( defaultConf ).resolve()
    
    lazy val ccFilePattern = Seq("*.c")
    lazy val cxxFilePattern = Seq("*.cpp", "*.cxx")
    
    lazy val buildRootDirectory = file( conf.getString( "build.rootdirectory" ) ).getAbsoluteFile
    
    type SubProjectBuilders = Seq[Project => Project]
    
    private lazy val allProjectVals : Seq[Project] = ReflectUtilities.allVals[Project](this).values.toSeq
    private lazy val nativeProjectVals : Seq[NativeProject] = ReflectUtilities.allVals[NativeProject](this).values.toSeq
    
    def nativeProjects = nativeProjectVals
    
    /**
      * This is the general entry point for sbt. So we do some checking here too
      */
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
    
    // TODO: Currently not thread/process safe. Fix!
    case class PlatformConfig private ( private val cacheFile : File, private val configMap : immutable.Map[String, String])
    {
        def add( k : String, v : => String )
        {
            if ( configMap.contains(k) ) configMap
            else
            {
                val next = new PlatformConfig( cacheFile, configMap + (k -> v) )
                PlatformConfig.saveState( next )
                next
            }
        }
        
        def get(k : String) = configMap.get(k)
    }
    
    object PlatformConfig
    {
        def apply( cacheFile: File ) = loadState( cacheFile )
        
        private def saveState( platformConfig : PlatformConfig )
        {
            val lines = platformConfig.configMap.map { case (k, v) => "%s,%s".format( k, v ) }.mkString("\n")
            IO.write( platformConfig.cacheFile, lines )
        }
        
        private def loadState( cacheFile : File ) =
        {
            val kvp = if ( cacheFile.exists )
            {
                val lines = IO.readLines( cacheFile )
                lines.map( _.split(",").map( _.trim ) ).map( x => (x(0), x(1)) ).toMap
            }
            else Map[String, String]()
            
            new PlatformConfig( cacheFile, kvp )
        }
    }
    
    def configurations : Set[Environment]
    
    /**
     * Override this in your project to do appropriate checks on the build environment
     */
    def checkEnvironment( log : Logger, env : Environment ) = { }
    
    val compiler = TaskKey[Compiler]("native-compiler")
    val buildEnvironment = TaskKey[Environment]("native-build-environment")
    val nativePlatformConfig = TaskKey[PlatformConfig]("native-platform-config")
    val rootBuildDirectory = TaskKey[File]("native-root-build-dir", "Build root directory (for the config, not the project)")
    val projectBuildDirectory = TaskKey[File]("native-project-build-dir", "Build directory for this config and project")
    val stateCacheDirectory = TaskKey[File]("native-state-cache-dir", "Build state cache directory")
    val projectDirectory = TaskKey[File]("native-project-dir", "Project directory")
    val sourceDirectories = TaskKey[Seq[File]]("native-source-dirs", "Source directories")
    val includeDirectories = TaskKey[Seq[File]]("native-include-dirs", "Include directories")
    val systemIncludeDirectories = TaskKey[Seq[File]]("native-system-include-dirs", "System include directories")
    val linkDirectories = TaskKey[Seq[File]]("native-link-dirs", "Link directories")
    val nativeLibraries = TaskKey[Seq[String]]("native-libraries", "All native library dependencies for this project")
    val ccSourceFiles = TaskKey[Seq[File]]("native-cc-source-files", "All C source files for this project")
    val cxxSourceFiles = TaskKey[Seq[File]]("native-cxx-source-files", "All C++ source files for this project")
    val ccSourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("native-cc-source-files-with-deps", "All C source files with dependencies for this project")
    val cxxSourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("native-cxx-source-files-with-deps", "All C++ source files with dependencies for this project")
    val objectFiles = TaskKey[Seq[File]]("native-object-files", "All object files for this project" )
    val nativeExe = TaskKey[File]("native-exe", "Executable built by this project (if appropriate)" )
    val nativeRun = TaskKey[Unit]("native-run", "Perform a native run of this project" )
    val testProject = TaskKey[Project]("native-test-project", "The test sub-project for this project")
    val nativeTest = TaskKey[Option[(File, File)]]("native-test-run", "Run the native test, returning the files with stdout and stderr respectively")
    val test = TaskKey[Unit]("test", "Run the test associated with this project")
    val runEnvironmentVariables = TaskKey[Seq[(String, String)]]("native-run-env-vars", "Environment variables to be set for test runs")
    val testEnvironmentVariables = TaskKey[Seq[(String, String)]]("native-test-env-vars", "Environment variables to be set for test runs")
    val cleanAll = TaskKey[Unit]("native-clean-all", "Clean the entire build directory")
    val ccCompileFlags = TaskKey[Seq[String]]("native-cc-flags", "Native C compile flags")
    val cxxCompileFlags = TaskKey[Seq[String]]("native-cxx-flags", "Native C++ compile flags")
    val linkFlags = TaskKey[Seq[String]]("native-link-flags", "Native link flags")
    

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
        
        //val envCheckFile = buildTargetDirectory / env.conf.pathDirs( .foldLeft( td )( _ / _ )
        val envCheckFile = env.conf.targetDirectory(buildRootDirectory) / "EnvHealthy.txt"
        
        if ( !envCheckFile.exists )
        {
            checkEnvironment( state.log, env )
            IO.write( envCheckFile, "HEALTHY" )
        }
        
        state.copy( attributes=updatedAttributes )
    }
    
    case class NativeProject( val p : Project, val subProjectBuilders : SubProjectBuilders, val dependencies : Seq[ProjectReference] )
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
            
            new NativeProject( newP, subProjectBuilders, dependencies ++ others )
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
            
            new NativeProject( newP, subProjectBuilders, dependencies ++ others )
        }
        
        //def addSubProject( spb : Project => Project ) = new NativeProject( p, spb +: subProjectBuilders )
    }
    
    implicit def toProject( rnp : NativeProject ) = rnp.p
    implicit def toProjectRef( rnp : NativeProject ) : ProjectReference = LocalProject(rnp.p.id)
    
    
    object NativeProject
    {
        // A selection of useful default settings from the standard sbt config
        lazy val relevantSbtDefaultSettings = Seq[Sett](
            watchTransitiveSources          <<= Defaults.watchTransitiveSourcesTask,                
            watch                           <<= Defaults.watchSetting
        )
        
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]], subProjectBuilders : SubProjectBuilders = Seq() ) =
        {
            val defaultSettings = relevantSbtDefaultSettings ++ Seq(
                name                        := _name,
                
                projectDirectory            <<= baseDirectory map { bd => (bd / _projectDirectory.toString) },
            
                commands                    ++= (BasicCommands.allBasicCommands :+ setBuildConfigCommand),
                
                buildEnvironment            <<= state map
                { s =>
                    val beo = s.attributes.get( envKey )
                    
                    if ( beo.isEmpty ) sys.error( "Please set a build configuration using the %s command".format(nativeBuildConfigurationCommandName) )
                    
                    beo.get
                },
                
                //target                    <<= baseDirectory { _ / "target" / "native" },
                target                      := buildRootDirectory,
                
                historyPath                 <<= target { t => Some(t / ".history") },
                
                rootBuildDirectory          <<= (target, buildEnvironment) map { case (td, be) => be.conf.targetDirectory(td) },
                
                clean                       <<= (rootBuildDirectory) map { rbd => IO.delete(rbd) },
                
                cleanAll                    <<= (target) map { td => IO.delete(td) },
                
                compiler                    <<= (buildEnvironment) map { _.compiler },
                
                projectBuildDirectory       <<= (rootBuildDirectory, name) map
                { case (rbd, n) =>
                
                    val dir = rbd / n
                    
                    IO.createDirectory(dir)
                    
                    dir
                },
                
                stateCacheDirectory         <<= (projectBuildDirectory) map { _ / "state-cache"  },
                
                includeDirectories          <<= (projectDirectory) map { pd => Seq(pd / "interface", pd / "include") },
                
                systemIncludeDirectories    <<= (compiler) map { _.defaultIncludePaths },
                
                linkDirectories             <<= (compiler) map { _.defaultLibraryPaths },
                
                nativeLibraries             <<= (projectBuildDirectory) map { _ => Seq() },
                
                sourceDirectories           <<= (projectDirectory) map { pd => Seq(pd / "source") },
                
                ccSourceFiles               <<= (sourceDirectories) map { _.flatMap
                { sd =>
                    ccFilePattern.flatMap( fp => (sd * fp).get )
                } },
                
                cxxSourceFiles              <<= (sourceDirectories) map { _.flatMap
                { sd =>
                    cxxFilePattern.flatMap( fp => (sd * fp).get )
                } },
                
                ccCompileFlags              <<= (compiler) map { _.ccDefaultFlags },
                
                cxxCompileFlags             <<= (compiler) map { _.cxxDefaultFlags },
                
                linkFlags                   <<= (compiler) map { _.linkDefaultFlags },
                
                ccSourceFilesWithDeps       <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, ccSourceFiles, ccCompileFlags, streams) flatMap
                {
                    case (c, bd, scd, ids, sids, sfs, cfs, s) =>
                    
                    // Calculate dependencies
                    def findDependencies( sourceFile : File ) : Seq[File] =
                    {
                        val depGen = c.findHeaderDependencies( s.log, bd, ids, sids, sourceFile, cfs )
                        
                        depGen.runIfNotCached( scd, Seq(sourceFile) )
                        
                        IO.readLines(depGen.resultPath).map( file )
                    }
                    
                    sfs.map( sf => toTask( () => (sf, findDependencies(sf)) ) ).join
                },
                
                cxxSourceFilesWithDeps      <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, cxxSourceFiles, cxxCompileFlags, streams) flatMap
                {
                    case (c, bd, scd, ids, sids, sfs, cfs, s) =>
                    
                    // Calculate dependencies
                    def findDependencies( sourceFile : File ) : Seq[File] =
                    {
                        val depGen = c.findHeaderDependencies( s.log, bd, ids, sids, sourceFile, cfs )
                        
                        depGen.runIfNotCached( scd, Seq(sourceFile) )
                        
                        IO.readLines(depGen.resultPath).map( file )
                    }
                    
                    sfs.map( sf => toTask( () => (sf, findDependencies(sf)) ) ).join
                },
                
                runEnvironmentVariables     := Seq(),
                
                testEnvironmentVariables    := Seq(),
                
                watchSources                <++= (ccSourceFilesWithDeps, cxxSourceFilesWithDeps) map
                { (ccsfd, cxxsfd) =>
                
                    (ccsfd ++ cxxsfd).flatMap { case (sf, deps) => (sf +: deps.toList) }.toList.distinct
                },
                
                objectFiles                 <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, ccSourceFilesWithDeps, cxxSourceFilesWithDeps, ccCompileFlags, cxxCompileFlags, streams) flatMap {
                    
                    case (c, bd, scd, ids, sids, ccfs, cxxfs, ccflags, cxxflags, s) =>
                    
                    val ccTasks = ccfs.map
                    {
                        case (sourceFile, dependencies) =>
                    
                        val blf = c.ccCompileToObj( s.log, bd, ids, sids, sourceFile, ccflags )
                                
                        toTask( () => blf.runIfNotCached( scd, sourceFile +: dependencies ) )
                    }
                    
                    val cxxTasks = cxxfs.map
                    {
                        case (sourceFile, dependencies) =>
                    
                        val blf = c.cxxCompileToObj( s.log, bd, ids, sids, sourceFile, cxxflags )
                                
                        toTask( () => blf.runIfNotCached( scd, sourceFile +: dependencies ) )
                    }
                    
                    (ccTasks ++ cxxTasks).join
                },
                
                nativeTest                  :=  None,
                test                        :=  Unit,
                
                exportedLibs                := Seq(),
                exportedLibDirectories      := Seq(),
                exportedIncludeDirectories  := Seq(),
                nativeExe                   := file("")
            )
            
            val p = Project( id=_name, base=_projectDirectory, settings=defaultSettings ++ _settings )
            
            new NativeProject(p, subProjectBuilders, Seq())
        }
    }

    object Library
    {
        private def buildLib( _name : String, _projectDirectory : File, settings : => Seq[sbt.Project.Setting[_]], isShared : Boolean, subProjectBuilders : SubProjectBuilders = Seq() ) =
        {
            val defaultSettings = Seq(
                exportedLibs <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, streams) map
                { case (c, projName, bd, scd, ofs, s) =>
                
                    val blf = if ( isShared ) c.buildSharedLibrary( s.log, bd, projName, ofs )
                    else c.buildStaticLibrary( s.log, bd, projName, ofs )
                    
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
                nativeExe <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, linkFlags, linkDirectories, nativeLibraries, streams) map
                { case (c, projName, bd, scd, ofs, lfs, lds, nls, s) =>
                
                    val blf = c.buildExecutable( s.log, bd, projName, lfs, lds, nls, ofs )
                    
                    blf.runIfNotCached( scd, ofs )
                },
                compile <<= nativeExe map { nc => sbt.inc.Analysis.Empty },
                nativeTest <<= (nativeExe, testEnvironmentVariables, stateCacheDirectory, streams) map
                { case (ncExe, tenvs, scd, s) =>

                    val resFile = file( ncExe + ".res" )
                    val stdoutFile = file( ncExe + ".stdout" )
                    
                    // TODO: This mutable var is evil. Return res more sanely from the closure
                    var res = 0
                    val tcf = FunctionWithResultPath( stdoutFile )
                    { _ =>
                        s.log.info( "Running test: " + ncExe )
                        
                        val pb = Process( ncExe.toString :: Nil, _projectDirectory, tenvs : _* )
                        val po = new ProcessOutputToString( mergeToStdout=true )
                        res = pb ! po
                        
                        IO.writeLines( stdoutFile, po.stdout )
                        IO.writeLines( resFile, Seq( res.toString ) )
                        
                    }
                    
                    tcf.runIfNotCached( scd, Seq(ncExe) )
                    
                    Some( (resFile, stdoutFile) )
                },
                test <<= (nativeTest, streams).map
                {
                    case (Some( (resFile, stdOutFile) ), s) =>
                    {
                        val res = IO.readLines(resFile).head.toInt
                        if ( res != 0 )
                        {
                            s.log.error( "Test failed: " + _name )
                            IO.readLines( stdOutFile ).foreach( l => s.log.info( l ) )
                            sys.error( "Non-zero exit code: " + res.toString )
                        }
                    }
                    case (None, s) =>
                      
                }
            )
            NativeProject( _name, _projectDirectory, defaultSettings ++ settings )
        }
    }

    object NativeExecutable
    {
        def apply( _name : String, _projectDirectory : File, settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val defaultSettings = Seq(
                nativeExe <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, linkFlags, linkDirectories, nativeLibraries, streams) map
                { case (c, projName, bd, scd, ofs, lfs, lds, nls, s) =>
                
                    val blf = c.buildExecutable( s.log, bd, projName, lfs, lds, nls, ofs )
                    
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



