package edu.washington.cs.laragraphulo.parfile

import java.io.File
import java.net.URL
import java.util.*


/**
 * An action to take on each file in a directory.
 */
@FunctionalInterface
interface FileAction {
  fun run(f: URL) {
    println(f.file)
  }
}

/**
 * For each input file, attempt to claim it by creating a ".claim" file in [lockDirectory].
 * If the claim is successful, run the [action] on the file.
 * Otherwise some other process claimed the file.
 *
 * Creates [lockDirectory] on construction if it does not exist.
 */
class ParallelFileMapper(
    inputFiles: List<File>,
    val lockDirectory: File,
    val action: FileAction
) : Runnable {
  val inputFiles: Iterator<File>

  init {
    if (!lockDirectory.exists()) {
      Thread.sleep((Math.random() * 1000).toLong())
      if (!lockDirectory.exists())
        lockDirectory.mkdirs()
    } else
      require(lockDirectory.isDirectory && lockDirectory.canRead()){
          "Problem with lockDirectory $lockDirectory"}

    inputFiles.forEach { f ->
          if (inputFiles.any { it.name == f.name && it !== f })
            throw IllegalArgumentException("One of the input files has the same name as another: ${f.name}")
        }

    val tmp = ArrayList(inputFiles)
    Collections.shuffle(tmp)
    this.inputFiles = tmp.iterator()
  }

  override fun run() {
    while (inputFiles.hasNext())
      tryFile(inputFiles.next())
  }

  private fun tryFile(file: File) {
    val claimFile = lockDirectory.resolve(file.name + ".claim")
    if (claimFile.createNewFile()) {
      action.run(file.toURI().toURL())
      Thread.`yield`()
    }
  }
}