package com.group2.wififtm

/**
 * Generic matrix utilities. Matrices are stored row-major in FloatArrays.
 * Indexing: element (i, j) of a [rows × cols] matrix M is at M[i * cols + j].
 * Column vectors are [n × 1] matrices.
 */
object MatUtils {

    /**
     * Returns A * B.
     * A is [aRows × inner], B is [inner × bCols].
     * Result is [aRows × bCols].
     */
    fun multiply(a: FloatArray, aRows: Int, inner: Int, b: FloatArray, bCols: Int): FloatArray {
        val c = FloatArray(aRows * bCols)
        for (i in 0 until aRows) {
            for (j in 0 until bCols) {
                var sum = 0f
                for (k in 0 until inner) sum += a[i * inner + k] * b[k * bCols + j]
                c[i * bCols + j] = sum
            }
        }
        return c
    }

    /**
     * Returns A^T.
     * A is [rows × cols]. Result is [cols × rows].
     */
    fun transpose(a: FloatArray, rows: Int, cols: Int): FloatArray {
        val t = FloatArray(rows * cols)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                t[j * rows + i] = a[i * cols + j]
            }
        }
        return t
    }

    /**
     * Returns the inverse of a square [n × n] matrix via Gauss-Jordan elimination.
     * Returns null if the matrix is singular.
     * For rotation matrices (orthogonal), prefer transpose() — it is exact and cheaper.
     */
    fun inverse(a: FloatArray, n: Int): FloatArray? {
        val aug = FloatArray(n * 2 * n)
        for (i in 0 until n) {
            for (j in 0 until n) aug[i * 2 * n + j] = a[i * n + j]
            aug[i * 2 * n + n + i] = 1f
        }

        for (col in 0 until n) {
            var pivotRow = col
            var pivotVal = kotlin.math.abs(aug[col * 2 * n + col])
            for (row in col + 1 until n) {
                val v = kotlin.math.abs(aug[row * 2 * n + col])
                if (v > pivotVal) { pivotVal = v; pivotRow = row }
            }
            if (pivotVal < 1e-9f) return null

            if (pivotRow != col) {
                for (j in 0 until 2 * n) {
                    val tmp = aug[col * 2 * n + j]
                    aug[col * 2 * n + j] = aug[pivotRow * 2 * n + j]
                    aug[pivotRow * 2 * n + j] = tmp
                }
            }

            val scale = aug[col * 2 * n + col]
            for (j in 0 until 2 * n) aug[col * 2 * n + j] /= scale

            for (row in 0 until n) {
                if (row == col) continue
                val factor = aug[row * 2 * n + col]
                for (j in 0 until 2 * n) aug[row * 2 * n + j] -= factor * aug[col * 2 * n + j]
            }
        }

        val inv = FloatArray(n * n)
        for (i in 0 until n) {
            for (j in 0 until n) inv[i * n + j] = aug[i * 2 * n + n + j]
        }
        return inv
    }
}
