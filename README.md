# AutoCare Hub — Billing Service

O Billing calcula e persiste orçamentos de ordens de serviço no seu próprio PostgreSQL. A API de OS é responsável por reservar as peças e publicar o pedido com uma cópia dos itens e preços. O Billing confirma o processamento após persistir o resultado. O Billing registra aprovação ou rejeição e emite o resultado. Com a integração habilitada, uma aprovação cria a cobrança QR e o webhook assinado atualiza o estado após consultar a Orders API.

## Contrato de mensageria

| Fila | Direção | Conteúdo |
| --- | --- | --- |
| `autocare.billing.generate-budget.v1` | OS → Billing | `requestId` e `orderId` como UUIDs e uma lista `items` com `itemId`, `description`, `quantity` e `unitPrice` |
| `autocare.os.budget-generated.v1` | Billing → OS | `requestId`, `orderId` e `totalAmount` |
| `autocare.billing.decide-budget.v1` | OS → Billing | `budgetId`, `orderId`, decisão, origem e motivo |
| `autocare.os.budget-decided.v1` | Billing → OS | `budgetId`, `orderId` e decisão |
| `autocare.billing.refund-repair.v1` | OS → Billing | `budgetId` e `orderId` da liberação de reparo que falhou |
| `autocare.os.payment-updated.v1` | Billing → OS | `budgetId`, `orderId`, valor e estado do pagamento |

Os valores monetários são decimais com duas casas. O Billing usa `requestId` como chave primária e guarda o orçamento e o resultado na mesma transação. O publicador marca o resultado como enviado após a confirmação de roteamento pelo RabbitMQ. A fila de entrada é durável e encaminha mensagens rejeitadas após as tentativas para a fila com sufixo `.dead`.

## Execução

```sh
mvn -B verify
```

O CI exige 80% de cobertura de linhas, publica o relatório JaCoCo e monta a imagem Docker. O relatório JaCoCo local fica em `target/site/jacoco/index.html`. Em uma execução do CI para o SHA da entrega, consulte o artefato `billing-coverage` na página da execução. [Workflow de CI](https://github.com/yxsbx/SOAT-FIAP-autocare-billing/actions/workflows/ci.yml)

Para o ensaio local com os dois bancos e o RabbitMQ, use o [Compose da API](https://github.com/yxsbx/SOAT-FIAP-autocare-api/blob/main/compose.phase4.yaml). Configure `BILLING_DB_URL`, `BILLING_DB_USER`, `BILLING_DB_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_USER` e `RABBITMQ_PASSWORD` para iniciar o Billing fora do Compose.


A consulta interna `GET /internal/v1/budgets/{budgetId}` exige `X-Internal-Service-Token`, configurado com `BILLING_INTERNAL_TOKEN`. Para testar o provedor, use o cenário BDD da API. O ambiente com credenciais de teste do Mercado Pago exige `MERCADO_PAGO_ENABLED`, `MERCADO_PAGO_ACCESS_TOKEN`, `MERCADO_PAGO_EXTERNAL_POS_ID`, `MERCADO_PAGO_WEBHOOK_SECRET` e a rota HTTPS `/webhooks/mercado-pago`. [Orders QR](https://www.mercadopago.com.br/developers/pt/docs/qr-code/payment-processing)

O Billing mantém a mesma chave de idempotência ao criar o QR. Depois de cinco falhas ou 15 minutos desde o registro da cobrança, a consulta interna retorna `REVIEW_REQUIRED` e as tentativas automáticas cessam. A reserva permanece enquanto o resultado for incerto. Um webhook assinado ainda pode reconciliar a cobrança a partir de uma consulta à Orders API. [Referência da Orders API](https://www.mercadopago.com.br/developers/pt/reference/in-person-payments/qr-code/orders/create-order/post)

Na compensação da liberação do reparo, Billing solicita o reembolso integral com uma chave de idempotência própria. Publica `REFUNDED` após confirmar valor e referência na Orders API. Uma solicitação sem confirmação após a janela fica em `REFUND_REVIEW_REQUIRED` e ainda aceita conciliação pelo webhook. [API de reembolso](https://www.mercadopago.com.br/developers/pt/reference/in-person-payments/qr-code/orders/refund-order/post)

### Teste externo do QR dinâmico

Crie a loja e o caixa usando as credenciais de teste. Configure `MERCADO_PAGO_EXTERNAL_POS_ID` com a referência externa do caixa e use o Access Token de teste no runtime do Billing. Na conta de teste vendedora, selecione **Modo de produção** na tela de Webhooks dessa conta de teste e configure notificações de **Order (Mercado Pago)** para a URL HTTPS `/webhooks/mercado-pago`. Configure o segredo de assinatura correspondente no mesmo runtime. [Criação de loja e caixa](https://www.mercadopago.com.br/developers/pt/docs/qr-code/create-store-and-pos) · [Notificações](https://www.mercadopago.com.br/developers/pt/docs/qr-code/notifications)

Gere o QR ao aprovar um orçamento, use a conta de teste compradora no aplicativo do Mercado Pago e confira a confirmação em Billing e na OS. Guarde o identificador da order do provedor e o `orderId` da OS como evidência. [Compra de teste](https://www.mercadopago.com.br/developers/pt/docs/qr-code/test-integration/test-purchase)

O contrato HTTP está em [OpenAPI](docs/api/openapi.yaml).
