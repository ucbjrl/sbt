/* sbt -- Simple Build Tool
 * Copyright 2010  Mark Harrah
 */
package sbt

import sbt.compiler.javac.{ IncrementalCompilerJavaTools, JavaCompiler, JavaTools }
import xsbti.{ Logger => _, _ }
import xsbti.compile.{ CompileOrder, GlobalsCache }
import CompileOrder.{ JavaThenScala, Mixed, ScalaThenJava }
import compiler._
import inc._
import Locate.DefinesClass
import java.io.File

object Compiler {
  val DefaultMaxErrors = 100

  final case class Inputs(compilers: Compilers, config: Options, incSetup: IncSetup)
  final case class Options(classpath: Seq[File], sources: Seq[File], classesDirectory: File, options: Seq[String], javacOptions: Seq[String], maxErrors: Int, sourcePositionMapper: Position => Position, order: CompileOrder)
  final case class IncSetup(analysisMap: File => Option[Analysis], definesClass: DefinesClass, skip: Boolean, cacheFile: File, cache: GlobalsCache, incOptions: IncOptions)
  private[sbt] trait JavaToolWithNewInterface extends JavaTool {
    def newJavac: IncrementalCompilerJavaTools
  }
  final case class Compilers(scalac: AnalyzingCompiler, javac: JavaTool) {
    final def newJavac: Option[IncrementalCompilerJavaTools] =
      javac match {
        case x: JavaToolWithNewInterface => Some(x.newJavac)
        case _                           => None
      }
  }
  final case class NewCompilers(scalac: AnalyzingCompiler, javac: JavaTools)

  def inputs(classpath: Seq[File], sources: Seq[File], classesDirectory: File, options: Seq[String], javacOptions: Seq[String], maxErrors: Int, sourcePositionMappers: Seq[Position => Option[Position]], order: CompileOrder)(implicit compilers: Compilers, incSetup: IncSetup, log: Logger): Inputs =
    new Inputs(
      compilers,
      new Options(classpath, sources, classesDirectory, options, javacOptions, maxErrors, foldMappers(sourcePositionMappers), order),
      incSetup
    )

  def compilers(cpOptions: ClasspathOptions)(implicit app: AppConfiguration, log: Logger): Compilers =
    {
      val scalaProvider = app.provider.scalaProvider
      compilers(ScalaInstance(scalaProvider.version, scalaProvider.launcher), cpOptions)
    }

  def compilers(instance: ScalaInstance, cpOptions: ClasspathOptions)(implicit app: AppConfiguration, log: Logger): Compilers =
    compilers(instance, cpOptions, None)

  def compilers(instance: ScalaInstance, cpOptions: ClasspathOptions, javaHome: Option[File])(implicit app: AppConfiguration, log: Logger): Compilers =
    {
      val javac =
        AggressiveCompile.directOrFork(instance, cpOptions, javaHome)
      val javac2 =
        JavaTools.directOrFork(instance, cpOptions, javaHome)
      // Hackery to enable both the new and deprecated APIs to coexist peacefully.
      case class CheaterJavaTool(newJavac: IncrementalCompilerJavaTools, delegate: JavaTool) extends JavaTool with JavaToolWithNewInterface {
        def compile(contract: JavacContract, sources: Seq[File], classpath: Seq[File], outputDirectory: File, options: Seq[String])(implicit log: Logger): Unit =
          javac.compile(contract, sources, classpath, outputDirectory, options)(log)
        def onArgs(f: Seq[String] => Unit): JavaTool = CheaterJavaTool(newJavac, delegate.onArgs(f))
      }
      compilers(instance, cpOptions, CheaterJavaTool(javac2, javac))
    }
  @deprecated("0.13.8", "Deprecated in favor of new sbt.compiler.javac package.")
  def compilers(instance: ScalaInstance, cpOptions: ClasspathOptions, javac: sbt.compiler.JavaCompiler.Fork)(implicit app: AppConfiguration, log: Logger): Compilers =
    {
      val javaCompiler = sbt.compiler.JavaCompiler.fork(cpOptions, instance)(javac)
      compilers(instance, cpOptions, javaCompiler)
    }
  @deprecated("0.13.8", "Deprecated in favor of new sbt.compiler.javac package.")
  def compilers(instance: ScalaInstance, cpOptions: ClasspathOptions, javac: JavaTool)(implicit app: AppConfiguration, log: Logger): Compilers =
    {
      val scalac = scalaCompiler(instance, cpOptions)
      new Compilers(scalac, javac)
    }
  def scalaCompiler(instance: ScalaInstance, cpOptions: ClasspathOptions)(implicit app: AppConfiguration, log: Logger): AnalyzingCompiler =
    {
      val launcher = app.provider.scalaProvider.launcher
      val componentManager = new ComponentManager(launcher.globalLock, app.provider.components, Option(launcher.ivyHome), log)
      val provider = ComponentCompiler.interfaceProvider(componentManager)
      new AnalyzingCompiler(instance, provider, cpOptions, log)
    }
  def apply(in: Inputs, log: Logger): Analysis =
    {
      import in.compilers._
      import in.config._
      import in.incSetup._
      apply(in, log, new LoggerReporter(maxErrors, log, sourcePositionMapper))
    }
  def apply(in: Inputs, log: Logger, reporter: xsbti.Reporter): Analysis =
    {
      import in.compilers._
      import in.config._
      import in.incSetup._
      val agg = new AggressiveCompile(cacheFile)
      // Here is some trickery to choose the more recent (reporter-using) java compiler rather
      // than the previously defined versions.
      // TODO - Remove this hackery in sbt 1.0.
      val javacChosen: xsbti.compile.JavaCompiler =
        in.compilers.newJavac.map(_.xsbtiCompiler).getOrElse(in.compilers.javac)
      agg(scalac, javacChosen, sources, classpath, CompileOutput(classesDirectory), cache, None, options, javacOptions,
        analysisMap, definesClass, reporter, order, skip, incOptions)(log)
    }

  private[sbt] def foldMappers[A](mappers: Seq[A => Option[A]]) =
    mappers.foldRight({ p: A => p }) { (mapper, mappers) => { p: A => mapper(p).getOrElse(mappers(p)) } }
}
