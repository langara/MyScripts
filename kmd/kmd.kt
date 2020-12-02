@file:DependsOn("com.offbytwo:docopt:0.6.0.20150202")
// TODO: use clikt instead of docopt: https://ajalt.github.io/clikt/

import org.docopt.Docopt
import java.io.File
import java.io.IOException


val usage = """
Collection of my custom commands (rewrite of: https://github.com/langara/ml.cmd)

Usage:
    kmd ( exec    | x  )  <subcmd> [<args>...]
    kmd ( shell   | s  )  <subcmd> [<args>...]
    kmd ( term    | t  )
    kmd ( ide     | i  )  <file>
    kmd ( edit    | e  )  <file>
    kmd ( openf   | o  )  <file>
    kmd ( lopenf  | lo )  <file>
    kmd ( play    | p  )  <file>
    kmd ( hist    | h  )  [<commands>...]
    kmd ( decomp  | de )  <file> [<dir>]
    kmd ( diff    | d  )  <file1> <file2> [<file3>]
    kmd ( fmgr    | f  )  <dir>
    kmd [-h | --help | -v | --version]

Options:
    -h, --help         Print the help page
    -v, --version      Print the version number

Commands:
    exec:              Exec given command in new process (and return immediately).
    shell:             Exec given command in bash shell, wait for it, then print its output.
    term:              Run the default terminal emulator in new process.
    ide:               Open file in IDE.
    edit:              Run the best text editor (or open a file in existing instance).
    openf:             Open file using best program available for given file type (now it is just:xdg-open)
    lopenf:            Open file specified inside given file using best program available
    play:              Play given multimedia file
    hist:              Adds given commands to bash history so they are available in new terminals under up key
    decomp:            Decompress archive file. Supports all most popular archive types.
    diff:              Run the best text editor in diff mode.
    fmgr:              Run the best file manager (or open a directory in existing instance).


Convenient way to use it from shell is through shortcuts (see MyScripts/bin dir and add it to PATH).

Then these example commands do the same:
    $ kmd exec xclock
    $ exec xclock
    $ x xclock

Next example:
    $ kmd edit ~/.bashrc
    $ edit ~/.bashrc
    $ e ~/.bashrc

Next example:
    $ kmd shell cat ~/.profile
    $ shell cat ~/.profile
    $ s cat ~/.profile
"""

val Any?.unit get() = Unit

val List<String>.print get() = forEach(::println)

data class ExecResult(val exitValue: Int, val stdOutAndErr: List<String>)

/**
 * Returns the output but ensures the exit value was 0 first
 * @throws IOException if exit value is not 0
 */
val ExecResult.out get() = if (exitValue != 0) throw IOException("Exit value: $exitValue") else stdOutAndErr

fun execStart(cmd: String, args: List<String> = emptyList(), dir: String? = null) = ProcessBuilder()
    .command(listOf(cmd) + args)
    .directory(dir?.let(::File))
    .redirectErrorStream(true)
    .start()

fun execBlocking(cmd: String, args: List<String> = emptyList(), dir: String? = null): ExecResult {
    val process = execStart(cmd, args, dir)
    val output = process.inputStream.bufferedReader().use { it.readLines() }
    val exit = process.waitFor()
    return ExecResult(exit, output)
}

/**
 * Execute given command (with optional args) in separate subprocess. Does not wait for it to end.
 * (the command should not expect any input or give any output or error)
 */
fun exec(cmd: String, vararg args: String, dir: String? = null) = execStart(cmd, args.toList(), dir).unit

/**
 * Runs given command in bash shell;
 * captures all its output (with error output merged in);
 * waits for the subprocess to finish;
 */
fun shell(cmd: String, vararg args: String, dir: String? = null) =
    execBlocking("bash", listOf("-c") + (listOf(cmd) + args).joinToString(" "), dir)

/**
 * Run the default terminal emulator in new process.
 */
fun term() = exec("x-terminal-emulator")

fun isVimRunning(serverName: String) = serverName in shell("vim", "--serverlist").out

fun ide(fileName: String) = shell("idea", "-e", fileName).out.print

/**
 * Run the best text editor (or open a file in existing instance).
 */
fun edit(
    fileName: String,
    vimExecName: String = "gvim",
    vimServerName: String = "EDITOR",
    addFileNames: List<String> = emptyList()
) {
    val args = mutableListOf("--servername", vimServerName)
    if (isVimRunning(vimServerName)) args.add("--remote")
    args.add(fileName)
    for (afn in addFileNames) args.add(afn)
    exec(vimExecName, *args.toTypedArray())
}

/**
 * Open file (or url) using best available application. Current implementation just use xdg-open.
 */
fun openf(fileName: String) = shell("xdg-open", fileName).out.print

fun lopenf(linkName: String, lines: Int = 1) {
    val reader = File(linkName).bufferedReader()
    reader.use {
        for (i in 1..lines) {
            val line = it.readLine() ?: break
            openf(line)
        }
    }
}

val String.isDir get() = File(this).isDirectory()

/**
 * Play given media file.
 */
fun play(fileName: String) = when {
    fileName.hasExt("mp3", "ogg", "wav", "flac") || fileName.isDir-> exec("audacious", fileName)
    fileName.hasExt("mpg", "mp4", "mkv", "avi") -> exec("smplayer", fileName)
    else -> throw IOException("Unknown media file format: $fileName")
}

/**
 * Add some commands to bash history. If user opens a new bash after,
 * he will have quick access to those commands using the up arrow key.
 */
fun hist(vararg cmds: String) = File("/home/marek/.bash_history")
    .appendText(cmds.joinToString(separator = "\n", postfix = "\n"))

fun String.hasExt(vararg extensions: String) = extensions.any { endsWith(".$it") }

fun decomp(fileName: String, dirName: String = "$fileName.dir") {
    shell("mkdir \"$dirName\"").out.print // TODO: use java/kotlin for creating dir instead of shell
    when {
        fileName.hasExt("tar")                           -> shell("tar -xvf  \"../$fileName\"", dir = dirName)
        fileName.hasExt("tar.gz", "tgz")                 -> shell("tar -xzvf \"../$fileName\"", dir = dirName)
        fileName.hasExt("tar.bz2", "tbz", "tbz2", "tb2") -> shell("tar -xjvf \"../$fileName\"", dir = dirName)
        fileName.hasExt("zip")                           -> shell("unzip     \"../$fileName\"", dir = dirName)
        fileName.hasExt("rar")                           -> shell("unrar x   \"../$fileName\"", dir = dirName)
        else -> throw IOException("Unknown archive type: $fileName")
    }.out.print
}

fun diff(fileName1: String, fileName2: String, fileName3: String? = null) {
    val addFileNames = fileName3?.let { listOf(fileName2, it) } ?: listOf(fileName2)
    edit(fileName1, "gvimdiff", "DIFF", addFileNames)
}

/**
 * Run the best file manager (or open a directory in an existing instance).
 */
fun fmgr(dirName: String) = edit(dirName, vimServerName = "FILEMANAGER")

fun kmd(vararg args: String) = kmd(args.toList())
fun kmd(argsList: List<String>) {

    val argsMap = Docopt(usage)
        .withVersion("0.0.1")
        .parse(argsList)

    val subcmd = argsMap["<subcmd>"] as String?
    @Suppress("UNCHECKED_CAST")
    val args = (argsMap["<args>"] as List<String>).toTypedArray()
    @Suppress("UNCHECKED_CAST")
    val commands = (argsMap["<commands>"] as List<String>).toTypedArray()
    val dir = argsMap["<dir>"] as String?
    val file = argsMap["<file>"] as String?
    val file1 = argsMap["<file1>"] as String?
    val file2 = argsMap["<file2>"] as String?
    val file3 = argsMap["<file3>"] as String?

    fun isCmd(vararg cmds: String) = cmds.any { argsMap[it] as Boolean }

    when {
        isCmd("exec", "x") -> exec(subcmd!!, *args)
        isCmd("shell", "s") -> shell(subcmd!!, *args).out.print
        isCmd("term", "t") -> term()
        isCmd("ide", "i") -> ide(file!!)
        isCmd("edit", "e") -> edit(file!!)
        isCmd("openf", "o") -> openf(file!!)
        isCmd("lopenf", "lo") -> lopenf(file!!)
        isCmd("play", "p") -> play(file!!)
        isCmd("hist", "h") -> hist(*commands)
        isCmd("decomp", "de") -> decomp(file!!, dir ?: "$file.dir")
        isCmd("diff", "d") -> diff(file1!!, file2!!, file3)
        isCmd("fmgr", "f") -> fmgr(dir!!)
    }
}


