package io.github.nitouge.cache.core.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * {@link CacheTemplate} 批量/级联辅助方法测试：
 * 分片加载 {@code chunkedLoad}/{@code chunkedLoadList} 与安全级联 {@code batchGetOrLoadCombined}。
 *
 */
public class CacheTemplateBatchTest {

    @Test
    public void chunkedLoad_splitsByChunkSizeAndMerges() {
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            keys.add(i);
        }
        List<Integer> chunkSizes = new ArrayList<>();

        Map<Integer, String> r = CacheTemplate.chunkedLoad(keys, 10, chunk -> {
            chunkSizes.add(chunk.size());
            Map<Integer, String> m = new HashMap<>();
            for (Integer k : chunk) {
                m.put(k, "v" + k);
            }
            return m;
        });

        assertThat(chunkSizes).containsExactly(10, 10, 3); // 23 = 10 + 10 + 3
        assertThat(r).hasSize(23);
        assertThat(r.get(0)).isEqualTo("v0");
        assertThat(r.get(22)).isEqualTo("v22");
    }

    @Test
    public void chunkedLoad_smallList_singleCall() {
        AtomicInteger calls = new AtomicInteger();
        CacheTemplate.chunkedLoad(Arrays.asList(1, 2), 10, chunk -> {
            calls.incrementAndGet();
            return Collections.singletonMap(chunk.get(0), "x");
        });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    public void chunkedLoad_emptyOrNull_returnsEmpty_withoutCallingLoader() {
        AtomicInteger calls = new AtomicInteger();
        Map<Integer, String> r1 = CacheTemplate.chunkedLoad(Collections.emptyList(), 10, c -> {
            calls.incrementAndGet();
            return null;
        });
        Map<Integer, String> r2 = CacheTemplate.chunkedLoad(null, 10, c -> {
            calls.incrementAndGet();
            return null;
        });
        assertThat(r1).isEmpty();
        assertThat(r2).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    public void chunkedLoadList_splitsAndConcatsInOrder() {
        List<String> r = CacheTemplate.chunkedLoadList(Arrays.asList(1, 2, 3, 4, 5), 2, chunk -> {
            List<String> l = new ArrayList<>();
            for (Integer k : chunk) {
                l.add("v" + k);
            }
            return l;
        });
        assertThat(r).containsExactly("v1", "v2", "v3", "v4", "v5");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void batchGetOrLoadCombined_buildsDtoWithoutMutatingMain_andToleratesMissingRelated() {
        CacheTemplate t = mock(CacheTemplate.class, CALLS_REAL_METHODS);
        // 最底层 batchGetOrLoad(4-arg) 模拟"全未命中"：直接用 loader 加载（不接缓存）
        doAnswer(inv -> {
            Object keys = inv.getArgument(1);
            Function loader = inv.getArgument(3);
            return loader.apply(keys);
        }).when(t).batchGetOrLoad(anyString(), anyList(), any(), any());

        Map<Long, String> related = new HashMap<>();
        related.put(1L, "orders-of-1"); // 仅 1L 有关联，2L 缺失

        List<String> result = t.batchGetOrLoadCombined(
                "user",
                Arrays.asList(1L, 2L),
                (Long id) -> id,                            // keyExtractor：主对象即 id
                ids -> new ArrayList<>(ids),                // 主数据加载器：返回 id 本身作为"主对象"
                mains -> related,                           // 关联加载器：返回 主key -> 关联（仅返回，不改主对象）
                (id, rel) -> "user" + id + ":" + rel);      // assembler：组合出新对象

        assertThat(result).containsExactly("user1:orders-of-1", "user2:null");
    }
}
