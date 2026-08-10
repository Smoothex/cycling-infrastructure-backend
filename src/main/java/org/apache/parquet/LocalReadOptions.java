package org.apache.parquet;

import org.apache.parquet.bytes.HeapByteBufferAllocator;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.filter2.compat.FilterCompat;

import java.util.HashMap;

import static org.apache.parquet.format.converter.ParquetMetadataConverter.NO_FILTER;

/**
 * Creates local-file read options without initializing Hadoop configuration classes.
 *
 * <p>{@link ParquetReadOptions} is an internal Apache Parquet type whose public builder currently
 * initializes MapReduce classes even when given a {@link PlainParquetConfiguration}. Keeping this
 * small bridge in Parquet's package allows the application to use the package-private constructor
 * and avoid pulling a Hadoop client runtime into a local GeoParquet reader.</p>
 */
public final class LocalReadOptions {

    private LocalReadOptions() {
    }

    public static ParquetReadOptions create(CompressionCodecFactory codecFactory) {
        return new ParquetReadOptions(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                FilterCompat.NOOP,
                NO_FILTER,
                codecFactory,
                new HeapByteBufferAllocator(),
                8 * 1024 * 1024,
                new HashMap<>(),
                null,
                null,
                new PlainParquetConfiguration()
        );
    }
}
