package portfolio.gateway;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Rejects an oversized body before forwarding its bytes to the String accumulator. */
final class BoundedBodyHandler implements HttpResponse.BodyHandler<String> {
    private final int maxBytes;

    BoundedBodyHandler(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("body limit must be positive");
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo response) {
        // Preserve the JDK handler's charset selection and decoding across buffer boundaries.
        return new Subscriber(HttpResponse.BodyHandlers.ofString().apply(response), maxBytes);
    }

    static final class Subscriber implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> downstream;
        private final int maxBytes;
        private Flow.Subscription subscription;
        private int received;
        private boolean finished;

        Subscriber(HttpResponse.BodySubscriber<String> downstream, int maxBytes) {
            this.downstream = downstream;
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<String> getBody() { return downstream.getBody(); }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            downstream.onSubscribe(incoming);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            long batchBytes = 0;
            for (ByteBuffer buffer : buffers) batchBytes += buffer.remaining();
            if (batchBytes > maxBytes - received) {
                finished = true;
                subscription.cancel();
                downstream.onError(new IOException("upstream body exceeds " + maxBytes + " bytes"));
                return;
            }
            received += (int) batchBytes;
            downstream.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (finished) return;
            finished = true;
            downstream.onError(error);
        }

        @Override
        public void onComplete() {
            if (finished) return;
            finished = true;
            downstream.onComplete();
        }
    }
}
