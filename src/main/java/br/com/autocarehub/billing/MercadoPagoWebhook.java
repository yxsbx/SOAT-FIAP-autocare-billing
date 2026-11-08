package br.com.autocarehub.billing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "billing.mercado-pago.enabled", havingValue = "true")
class MercadoPagoWebhook {
    private static final Pattern TIMESTAMP = Pattern.compile("(?:^|,\\s*)ts=(\\d{1,16})(?=,|$)");
    private static final Pattern SIGNATURE = Pattern.compile("(?:^|,\\s*)v1=([a-fA-F0-9]{64})(?=,|$)");
    private final byte[] secret;
    private final PaymentWorkflow payments;

    MercadoPagoWebhook(@Value("${MERCADO_PAGO_WEBHOOK_SECRET}") String secret, PaymentWorkflow payments) {
        if (secret.isBlank()) {
            throw new IllegalArgumentException("Mercado Pago webhook secret is required");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.payments = payments;
    }

    @PostMapping("/webhooks/mercado-pago")
    void receive(
            @RequestParam("data.id") String providerOrderId,
            @RequestHeader("x-signature") String signature,
            @RequestHeader("x-request-id") String requestId) {
        var timestamp = TIMESTAMP.matcher(signature);
        var mac = SIGNATURE.matcher(signature);
        if (!providerOrderId.matches("[A-Za-z0-9_-]{1,128}")
                || !requestId.matches("[A-Za-z0-9_-]{1,128}")
                || !timestamp.find()
                || !mac.find()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String manifest = "id:" + providerOrderId.toLowerCase(Locale.ROOT) + ";request-id:" + requestId + ";ts:"
                + timestamp.group(1) + ";";
        byte[] signatureBytes = HexFormat.of().parseHex(mac.group(1));
        if (!MessageDigest.isEqual(signatureBytes, hmac(manifest))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        payments.reconcile(providerOrderId);
    }

    private byte[] hmac(String manifest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to validate Mercado Pago signature", error);
        }
    }
}
