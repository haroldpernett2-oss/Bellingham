package com.bellingham.optimizer

class UserService : IUserService.Stub() {

    override fun execCommand(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
