library(bvls)

### START OF GENERATED SETTINGS ###
### :setting matrix_height      ###
### :setting matrix_width       ###
### :setting matrix_file_path   ###
### :setting vector_file_path   ###
### :setting output_file_path   ###
### END OF GENERATED SETTINGS   ###

# Read the input matrix
matrix_file <- file(matrix_file_path, "rb")
A <- matrix(
  readBin(
    matrix_file,
    numeric(),
    matrix_height * matrix_width,
    size = 8,
    endian = "big"
  ),
  nrow = matrix_height, ncol = matrix_width, byrow=TRUE
)
close(matrix_file)
A[is.nan(A)] <- 0

# Read the output vector
vector_file <- file(vector_file_path, "rb")
b <- readBin(vector_file, numeric(), matrix_height, size = 8, endian = "big")
close(vector_file)
b[is.nan(b)] <- 0

# Compute the NNLS fit
fit_outcome <- bvls(A, b, bl = rep(0, matrix_width), bu = rep(Inf, matrix_width))

# Write the coefficients to the output file
output_file <- file(output_file_path, "wb")
writeBin(fit_outcome$x, output_file, size = 8, endian = "big")
close(output_file)
