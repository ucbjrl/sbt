package sbt.compiler.javac

import java.io.File
import java.net.URLClassLoader

import sbt._
import org.specs2.Specification
import xsbti.{ Severity, Problem }

object JavaCompilerSpec extends Specification {
  def is = s2"""

  This is a specification for forking + inline-running of the java compiler, and catching Error messages


  Compiling a java file with local javac should
    compile a java file                      ${works(local)}
    issue errors and warnings                ${findsErrors(local)}

  Compiling a file with forked javac should
     compile a java file                     ${works(forked)}
     issue errors and warnings               ${findsErrors(forked)}
     yield the same errors as local javac    $forkSameAsLocal

  Documenting a file with forked javadoc should
      document a java file                  ${docWorks(forked)}
      find errors in a java file            ${findsDocErrors(forked)}
  """

  // TODO - write a test to ensure that java .class files wind up in the right spot, and we can call the compiled java code.
  def docWorks(compiler: JavaTools) = IO.withTemporaryDirectory { out =>
    val (result, problems) = doc(compiler, Seq(knownSampleGoodFile), Seq("-d", out.getAbsolutePath))
    val compiled = result must beTrue
    val indexExists = (new File(out, "index.html")).exists must beTrue setMessage ("index.html does not exist!")
    val classExists = (new File(out, "good.html")).exists must beTrue setMessage ("good.html does not exist!")
    compiled and classExists and indexExists
  }

  def works(compiler: JavaTools) = IO.withTemporaryDirectory { out =>
    val (result, problems) = compile(compiler, Seq(knownSampleGoodFile), Seq("-deprecation", "-d", out.getAbsolutePath))
    val compiled = result must beTrue
    val classExists = (new File(out, "good.class")).exists must beTrue
    val cl = new URLClassLoader(Array(out.toURI.toURL))
    val clazzz = cl.loadClass("good")
    val mthd = clazzz.getDeclaredMethod("test")
    val testResult = mthd.invoke(null)
    val canRun = mthd.invoke(null) must equalTo("Hello")
    compiled and classExists and canRun
  }

  def findsErrors(compiler: JavaTools) = {
    val (result, problems) = compile(compiler, Seq(knownSampleErrorFile), Seq("-deprecation"))
    val errored = result must beFalse
    val foundErrorAndWarning = problems must haveSize(5)
    val hasKnownErrors = problems.toSeq must contain(errorOnLine(1), warnOnLine(7))
    errored and foundErrorAndWarning and hasKnownErrors
  }

  def findsDocErrors(compiler: JavaTools) = IO.withTemporaryDirectory { out =>
    val (result, problems) = doc(compiler, Seq(knownSampleErrorFile), Seq("-d", out.getAbsolutePath))
    val errored = result must beTrue
    val foundErrorAndWarning = problems must haveSize(2)
    val hasKnownErrors = problems.toSeq must contain(errorOnLine(3), errorOnLine(4))
    errored and foundErrorAndWarning and hasKnownErrors
  }

  def lineMatches(p: Problem, lineno: Int): Boolean =
    p.position.line.isDefined && (p.position.line.get == lineno)
  def isError(p: Problem): Boolean = p.severity == Severity.Error
  def isWarn(p: Problem): Boolean = p.severity == Severity.Warn

  def errorOnLine(lineno: Int) =
    beLike[Problem]({
      case p if lineMatches(p, lineno) && isError(p) => ok
      case _                                         => ko
    })
  def warnOnLine(lineno: Int) =
    beLike[Problem]({
      case p if lineMatches(p, lineno) && isWarn(p) => ok
      case _                                        => ko
    })

  def forkSameAsLocal = {
    val (fresult, fproblems) = compile(forked, Seq(knownSampleErrorFile), Seq("-deprecation"))
    val (lresult, lproblems) = compile(local, Seq(knownSampleErrorFile), Seq("-deprecation"))
    val sameResult = fresult must beEqualTo(lresult)

    val pResults = for ((f, l) <- fproblems zip lproblems) yield {
      val sourceIsSame =
        if (f.position.sourcePath.isDefined) (f.position.sourcePath.get must beEqualTo(l.position.sourcePath.get)).setMessage(s"${f.position} != ${l.position}")
        else l.position.sourcePath.isDefined must beFalse
      val lineIsSame =
        if (f.position.line.isDefined) f.position.line.get must beEqualTo(l.position.line.get)
        else l.position.line.isDefined must beFalse
      val severityIsSame = f.severity must beEqualTo(l.severity)
      // TODO - We should check to see if the levenshtein distance of the messages is close...
      sourceIsSame and lineIsSame and severityIsSame
    }
    val errorsAreTheSame = pResults.reduce(_ and _)
    sameResult and errorsAreTheSame
  }

  def compile(c: JavaTools, sources: Seq[File], args: Seq[String]): (Boolean, Array[Problem]) = {
    val log = Logger.Null
    val reporter = new LoggerReporter(10, log)
    val result = c.compile(sources, args)(log, reporter)
    (result, reporter.problems)
  }

  def doc(c: JavaTools, sources: Seq[File], args: Seq[String]): (Boolean, Array[Problem]) = {
    val log = Logger.Null
    val reporter = new LoggerReporter(10, log)
    val result = c.doc(sources, args)(log, reporter)
    (result, reporter.problems)
  }

  // TODO - Create one with known JAVA HOME.
  def forked = JavaTools(JavaCompiler.fork(), Javadoc.fork())

  def local =
    JavaTools(
      JavaCompiler.local.getOrElse(sys.error("This test cannot be run on a JRE, but only a JDK.")),
      Javadoc.local.getOrElse(Javadoc.fork())
    )

  def cwd =
    (new File(new File(".").getAbsolutePath)).getCanonicalFile

  def knownSampleErrorFile =
    new java.io.File(getClass.getResource("test1.java").toURI)

  def knownSampleGoodFile =
    new java.io.File(getClass.getResource("good.java").toURI)
}