package science.atlarge.grademl.core.math

import science.atlarge.grademl.core.util.asRPathString
import science.atlarge.grademl.core.util.instantiateRScript
import science.atlarge.grademl.core.util.runRScript
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Path

object NonNegativeLeastSquares {

    private const val MATRIX_FILENAME = "nnls-matrix.bin"
    private const val VECTOR_FILENAME = "nnls-vector.bin"
    private const val OUTPUT_FILENAME = "nnls-output.bin"
    private const val SCRIPT_FILENAME = "nnls.R"

    fun fit(matrix: Array<DoubleArray>, vector: DoubleArray, scratchDirectory: Path): DoubleArray {
        // Check that the input dimensions are sensible
        val matrixHeight = matrix.size
        require(matrixHeight > 0) { "Matrix must not be empty" }
        val matrixWidth = matrix[0].size
        require(matrix.all { it.size == matrixWidth }) { "Matrix must be rectangular" }
        require(matrixWidth > 0) { "Matrix must not be empty" }
        require(vector.size == matrixHeight) { "Matrix and vector heights must match" }

        require(matrixHeight.toLong() * matrixWidth * 8 <= 128L * 1024 * 1024) {
            "Matrix exceeds 128MB, stopping to prevent disk flooding and unreasonable execution times"
        }

        // Make sure that the scratch directory exists
        scratchDirectory.toFile().mkdirs()

        // Write the matrix and vector data to binary files
        val matrixFile = scratchDirectory.resolve(MATRIX_FILENAME).toFile()
        val vectorFile = scratchDirectory.resolve(VECTOR_FILENAME).toFile()
        writeMatrix(matrix, matrixFile)
        writeVector(vector, vectorFile)

        // Instantiate the R script that computes the NNLS fit
        val rScriptFile = scratchDirectory.resolve(SCRIPT_FILENAME).toFile()
        val outputFile = scratchDirectory.resolve(OUTPUT_FILENAME).toFile()
        instantiateRScript(
            rScriptFile, mapOf(
                "matrix_height" to matrixHeight.toString(),
                "matrix_width" to matrixWidth.toString(),
                "matrix_file_path" to matrixFile.asRPathString(),
                "vector_file_path" to vectorFile.asRPathString(),
                "output_file_path" to outputFile.asRPathString()
            )
        )

        // Make sure the output file does not exist
        if (outputFile.exists()) outputFile.delete()

        // Run the R-based NNLS solver
        if (!runRScript(rScriptFile)) throw IllegalStateException("NNLS solver did not complete successfully")

        // Read the resulting fit vector from the output file
        val resultVector = readVector(outputFile)
        require(resultVector.size == matrixWidth) { "Output vector height must match matrix width" }

        // Clean up the scratch files
        matrixFile.delete()
        vectorFile.delete()
        outputFile.delete()

        return resultVector
    }

    private fun writeMatrix(matrix: Array<DoubleArray>, file: File) {
        DataOutputStream(file.outputStream().buffered(1024 * 1024)).use { outStream ->
            for (row in matrix) {
                for (value in row) {
                    outStream.writeDouble(value)
                }
            }
        }
    }

    private fun writeVector(vector: DoubleArray, file: File) {
        DataOutputStream(file.outputStream().buffered(1024 * 1024)).use { outStream ->
            for (value in vector) {
                outStream.writeDouble(value)
            }
        }
    }

    private fun readVector(file: File): DoubleArray {
        val fileLength = file.length()
        require(fileLength % 8 == 0L) { "Vector file must be an integer multiple of 8 bytes long" }
        val vectorLength = (fileLength / 8).toInt()
        val vector = DoubleArray(vectorLength)
        DataInputStream(file.inputStream().buffered()).use { inStream ->
            for (i in 0 until vectorLength) {
                vector[i] = inStream.readDouble()
            }
        }
        return vector
    }

}