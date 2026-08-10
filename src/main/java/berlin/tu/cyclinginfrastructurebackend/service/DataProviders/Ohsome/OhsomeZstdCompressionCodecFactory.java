package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import com.github.luben.zstd.Zstd;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Minimal local decompression support for the ZSTD GeoParquet returned by ohsome v2. */
final class OhsomeZstdCompressionCodecFactory implements CompressionCodecFactory {

    private static final BytesInputDecompressor ZSTD = new ZstdDecompressor();
    private static final BytesInputDecompressor UNCOMPRESSED = new UncompressedDecompressor();

    @Override
    public BytesInputCompressor getCompressor(CompressionCodecName codecName) {
        throw new UnsupportedOperationException("The ohsome snapshot reader never writes Parquet files");
    }

    @Override
    public BytesInputDecompressor getDecompressor(CompressionCodecName codecName) {
        return switch (codecName) {
            case ZSTD -> ZSTD;
            case UNCOMPRESSED -> UNCOMPRESSED;
            default -> throw new IllegalArgumentException(
                    "Unsupported ohsome GeoParquet compression codec: " + codecName);
        };
    }

    @Override
    public void release() {
        // Stateless decompressors have no pooled native state to release.
    }

    private static final class ZstdDecompressor implements BytesInputDecompressor {

        @Override
        public BytesInput decompress(BytesInput compressed, int decompressedSize) throws IOException {
            byte[] output = new byte[decompressedSize];
            byte[] input = compressed.toByteArray();
            long actualSize = Zstd.decompress(output, input);
            verifySize(actualSize, decompressedSize);
            return BytesInput.from(output);
        }

        @Override
        public void decompress(ByteBuffer input,
                               int compressedSize,
                               ByteBuffer output,
                               int decompressedSize) throws IOException {
            if (compressedSize > input.remaining() || decompressedSize > output.remaining()) {
                throw new IOException("Invalid ZSTD input or output buffer size");
            }
            byte[] compressed = new byte[compressedSize];
            input.get(compressed);
            byte[] decompressed = new byte[decompressedSize];
            long actualSize = Zstd.decompress(decompressed, compressed);
            verifySize(actualSize, decompressedSize);
            output.put(decompressed);
        }

        @Override
        public void release() {
            // Static Zstd operations do not retain a context.
        }

        private static void verifySize(long actualSize, int expectedSize) throws IOException {
            if (Zstd.isError(actualSize)) {
                throw new IOException("ZSTD decompression failed: " + Zstd.getErrorName(actualSize));
            }
            if (actualSize != expectedSize) {
                throw new IOException(
                        "Unexpected ZSTD decompressed size: " + actualSize + " != " + expectedSize);
            }
        }
    }

    private static final class UncompressedDecompressor implements BytesInputDecompressor {

        @Override
        public BytesInput decompress(BytesInput input, int decompressedSize) throws IOException {
            byte[] bytes = input.toByteArray();
            if (bytes.length != decompressedSize) {
                throw new IOException("Unexpected uncompressed Parquet page size");
            }
            return BytesInput.from(bytes);
        }

        @Override
        public void decompress(ByteBuffer input,
                               int compressedSize,
                               ByteBuffer output,
                               int decompressedSize) throws IOException {
            if (compressedSize != decompressedSize
                    || compressedSize > input.remaining()
                    || decompressedSize > output.remaining()) {
                throw new IOException("Unexpected uncompressed Parquet buffer size");
            }
            ByteBuffer slice = input.slice();
            slice.limit(compressedSize);
            output.put(slice);
            input.position(input.position() + compressedSize);
        }

        @Override
        public void release() {
            // No resources.
        }
    }
}
