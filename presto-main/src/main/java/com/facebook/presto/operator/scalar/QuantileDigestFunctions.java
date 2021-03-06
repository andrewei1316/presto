/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.scalar;

import com.facebook.airlift.stats.QuantileDigest;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.Description;
import com.facebook.presto.spi.function.ScalarFunction;
import com.facebook.presto.spi.function.SqlType;
import com.facebook.presto.spi.type.StandardTypes;
import io.airlift.slice.Slice;

import static com.facebook.presto.operator.aggregation.FloatingPointBitsConverterUtil.sortableIntToFloat;
import static com.facebook.presto.operator.aggregation.FloatingPointBitsConverterUtil.sortableLongToDouble;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.util.Failures.checkCondition;
import static java.lang.Float.floatToRawIntBits;

public final class QuantileDigestFunctions
{
    public static final double DEFAULT_ACCURACY = 0.01;
    public static final long DEFAULT_WEIGHT = 1L;

    private QuantileDigestFunctions() {}

    @ScalarFunction("value_at_quantile")
    @Description("Given an input q between [0, 1], find the value whose rank in the sorted sequence of the n values represented by the qdigest is qn.")
    @SqlType(StandardTypes.DOUBLE)
    public static double valueAtQuantileDouble(@SqlType("qdigest(double)") Slice input, @SqlType(StandardTypes.DOUBLE) double quantile)
    {
        return sortableLongToDouble(valueAtQuantileBigint(input, quantile));
    }

    @ScalarFunction("value_at_quantile")
    @Description("Given an input q between [0, 1], find the value whose rank in the sorted sequence of the n values represented by the qdigest is qn.")
    @SqlType(StandardTypes.REAL)
    public static long valueAtQuantileReal(@SqlType("qdigest(real)") Slice input, @SqlType(StandardTypes.DOUBLE) double quantile)
    {
        return floatToRawIntBits(sortableIntToFloat((int) valueAtQuantileBigint(input, quantile)));
    }

    @ScalarFunction("value_at_quantile")
    @Description("Given an input q between [0, 1], find the value whose rank in the sorted sequence of the n values represented by the qdigest is qn.")
    @SqlType(StandardTypes.BIGINT)
    public static long valueAtQuantileBigint(@SqlType("qdigest(bigint)") Slice input, @SqlType(StandardTypes.DOUBLE) double quantile)
    {
        checkCondition(quantile >= 0 && quantile <= 1, INVALID_FUNCTION_ARGUMENT, "Quantile should be within bounds [0, 1], was: " + quantile);
        return new QuantileDigest(input).getQuantile(quantile);
    }

    @ScalarFunction("values_at_quantiles")
    @Description("For each input q between [0, 1], find the value whose rank in the sorted sequence of the n values represented by the qdigest is qn.")
    @SqlType("array(double)")
    public static Block valuesAtQuantilesDouble(@SqlType("qdigest(double)") Slice input, @SqlType("array(double)") Block percentilesArrayBlock)
    {
        QuantileDigest digest = new QuantileDigest(input);
        BlockBuilder output = DOUBLE.createBlockBuilder(null, percentilesArrayBlock.getPositionCount());
        for (int i = 0; i < percentilesArrayBlock.getPositionCount(); i++) {
            DOUBLE.writeDouble(output, sortableLongToDouble(digest.getQuantile(DOUBLE.getDouble(percentilesArrayBlock, i))));
        }
        return output.build();
    }

    @ScalarFunction("values_at_quantiles")
    @Description("For each input q between [0, 1], find the value whose rank in the sorted sequence of the n values represented by the qdigest is qn.")
    @SqlType("array(real)")
    public static Block valuesAtQuantilesReal(@SqlType("qdigest(real)") Slice input, @SqlType("array(double)") Block percentilesArrayBlock)
    {
        QuantileDigest digest = new QuantileDigest(input);
        BlockBuilder output = REAL.createBlockBuilder(null, percentilesArrayBlock.getPositionCount());
        for (int i = 0; i < percentilesArrayBlock.getPositionCount(); i++) {
            REAL.writeLong(output, floatToRawIntBits(sortableIntToFloat((int) digest.getQuantile(DOUBLE.getDouble(percentilesArrayBlock, i)))));
        }
        return output.build();
    }

    @ScalarFunction("values_at_quantiles")
    @Description("For each input q between [0, 1], find the value whose rank in the sorted sequence of the n values represented by the qdigest is qn.")
    @SqlType("array(bigint)")
    public static Block valuesAtQuantilesBigint(@SqlType("qdigest(bigint)") Slice input, @SqlType("array(double)") Block percentilesArrayBlock)
    {
        QuantileDigest digest = new QuantileDigest(input);
        BlockBuilder output = BIGINT.createBlockBuilder(null, percentilesArrayBlock.getPositionCount());
        for (int i = 0; i < percentilesArrayBlock.getPositionCount(); i++) {
            BIGINT.writeLong(output, digest.getQuantile(DOUBLE.getDouble(percentilesArrayBlock, i)));
        }
        return output.build();
    }

    @ScalarFunction("scale_qdigest")
    @Description("Scale a quantile digest according to a new weight")
    @SqlType("qdigest(double)")
    public static Slice scaleQuantileDigestDouble(@SqlType("qdigest(double)") Slice input, @SqlType(StandardTypes.DOUBLE) double scale)
    {
        return scaleQuantileDigest(input, scale);
    }

    @ScalarFunction("scale_qdigest")
    @Description("Scale a quantile digest according to a new weight")
    @SqlType("qdigest(real)")
    public static Slice scaleQuantileDigestReal(@SqlType("qdigest(real)") Slice input, @SqlType(StandardTypes.DOUBLE) double scale)
    {
        return scaleQuantileDigest(input, scale);
    }

    @ScalarFunction("scale_qdigest")
    @Description("Scale a quantile digest according to a new weight")
    @SqlType("qdigest(bigint)")
    public static Slice scaleQuantileDigestBigint(@SqlType("qdigest(bigint)") Slice input, @SqlType(StandardTypes.DOUBLE) double scale)
    {
        return scaleQuantileDigest(input, scale);
    }

    private static Slice scaleQuantileDigest(Slice input, double scale)
    {
        checkCondition(scale > 0, INVALID_FUNCTION_ARGUMENT, "Scale factor should be positive.");
        QuantileDigest digest = new QuantileDigest(input);
        digest.scale(scale);
        return digest.serialize();
    }

    public static double verifyAccuracy(double accuracy)
    {
        checkCondition(accuracy > 0 && accuracy < 1, INVALID_FUNCTION_ARGUMENT, "Percentile accuracy must be exclusively between 0 and 1, was %s", accuracy);
        return accuracy;
    }

    public static long verifyWeight(long weight)
    {
        checkCondition(weight > 0, INVALID_FUNCTION_ARGUMENT, "Percentile weight must be > 0, was %s", weight);
        return weight;
    }
}
