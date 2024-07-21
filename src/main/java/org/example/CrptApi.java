package org.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

public class CrptApi {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;
    private final Queue<Instant> requestTimes;
    private final int requestLimit;
    private final long timeWindowMillis;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.semaphore = new Semaphore(requestLimit);
        this.requestTimes = new ConcurrentLinkedQueue<>();
        this.requestLimit = requestLimit;
        this.timeWindowMillis = timeUnit.toMillis(1);
    }

    /**
     * Создает новый документ.
     *
     * @param document Объект документа, который нужно создать
     * @param signature Подпись для проверки
     * @throws InterruptedException Если поток был прерван
     * @throws IOException Если произошла ошибка ввода-вывода
     */
    public void createDocument(Document document, String signature) throws InterruptedException, IOException {
        semaphore.acquire();

        try {
            manageRequestTimes();

            String encodedSignature = Base64.getEncoder().encodeToString(signature.getBytes());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                    .timeout(Duration.ofMinutes(1))
                    .header("Content-Type", "application/json")
                    .header("Signature", encodedSignature)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(document)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Failed to create document: " + response.body());
            }
        } finally {
            semaphore.release();
        }
    }

    /**
     * Управляет временем отправки запросов, чтобы соблюдать лимит запросов в заданное временное окно.
     */
    private void manageRequestTimes() {
        Instant now = Instant.now();
        requestTimes.add(now);

        while (requestTimes.size() > requestLimit) {
            requestTimes.poll();
        }

        if (requestTimes.size() == requestLimit) {
            Instant oldestRequestTime = requestTimes.peek();
            assert oldestRequestTime != null;
            if (Duration.between(oldestRequestTime, now).toMillis() < timeWindowMillis) {
                try {
                    Thread.sleep(timeWindowMillis - Duration.between(oldestRequestTime, now).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Запись для описания документа.
     */
    public record Document(Description description, String doc_id, String doc_status, String doc_type, boolean importRequest,
                           String owner_inn, String participant_inn, String producer_inn, String production_date,
                           String production_type, Product[] products, String reg_date, String reg_number) {

        public record Description(String participantInn) {}

        public record Product(String certificate_document, String certificate_document_date, String certificate_document_number,
                              String owner_inn, String producer_inn, String production_date, String tnved_code,
                              String uit_code, String uitu_code) {}
    }

    public static void main(String[] args) {
        CrptApi api = new CrptApi(TimeUnit.MINUTES, 10);

        try {
            Document.Description description = new Document.Description("1234567890");

            Document.Product product = new Document.Product(
                    "Certificate Document", "2023-07-19", "12345",
                    "1234567890", "0987654321", "2023-07-19",
                    "1234567890", "UIT12345", "UITU12345"
            );

            Document document = new Document(
                    description, "DOC12345", "NEW", "LP_INTRODUCE_GOODS",
                    true, "1234567890", "1234567890", "0987654321",
                    "2023-07-19", "MANUFACTURED", new Document.Product[] { product },
                    "2023-07-19", "REG12345"
            );

            api.createDocument(document, "ваша подпись");
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }
}
