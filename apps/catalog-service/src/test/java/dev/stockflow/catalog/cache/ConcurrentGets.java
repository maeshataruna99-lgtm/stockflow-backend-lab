package dev.stockflow.catalog.cache;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.test.web.servlet.MockMvc;

/** Releases N identical GET requests at the same moment and returns their statuses and bodies. */
final class ConcurrentGets {

    record Reply(int status, String body) {
    }

    private ConcurrentGets() {
    }

    static List<Reply> run(MockMvc mockMvc, String path, int requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                Callable<Reply> call = () -> {
                    ready.countDown();
                    go.await();
                    var response = mockMvc.perform(get(path)).andReturn().getResponse();
                    return new Reply(response.getStatus(), response.getContentAsString());
                };
                futures.add(pool.submit(call));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> future : futures) {
                replies.add(future.get(30, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }
}
