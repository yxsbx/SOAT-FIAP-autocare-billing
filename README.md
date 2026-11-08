# AutoCare Hub — Billing Service

O Billing calcula e persiste orçamentos de ordens de serviço no seu próprio PostgreSQL. A API de OS é responsável por reservar as peças e publicar o pedido com uma cópia dos itens e preços. O Billing confirma o processamento após persistir o resultado. O Billing registra aprovação ou rejeição e emite o resultado. Com a integração habilitada, uma aprovação cria a cobrança QR e o webhook assinado atualiza o estado após consultar a Orders API.

## Contrato de mensageria

| Fila | Direção | Conteúdo |
| --- | --- | --- |
| `autocare.billing.generate-budget.v1` | OS → Billing | `requestId` e `orderId` como UUIDs e uma lista `items` com `itemId`, `description`, `quantity` e `unitPrice` |
| `autocare.os.budget-generated.v1` | Billing → OS | `requestId`, `orderId` e `totalAmount` |
| `autocare.billing.decide-budget.v1` | OS → Billing | `budgetId`, `orderId`, decisão, origem e motivo |
| `autocare.os.budget-decided.v1` | Billing → OS | `budgetId`, `orderId` e decisão |
| `autocare.os.payment-updated.v1` | Billing → OS | `budgetId`, `orderId`, valor e estado do pagamento |

Os valores monetários são decimais com duas casas. O Billing usa `requestId` como chave primária e guarda o orçamento e o resultado na mesma transação. O publicador marca o resultado como enviado após a confirmação de roteamento pelo RabbitMQ. A fila de entrada é durável e encaminha mensagens rejeitadas após as tentativas para a fila com sufixo `.dead`.

## Execução

```sh
mvn -B verify
```

O CI executa o mesmo comando, exige 80% de cobertura de linhas, publica o relatório JaCoCo e monta a imagem Docker. Para o ensaio local com os dois bancos e o RabbitMQ, use o [Compose da API](https://github.com/yxsbx/SOAT-FIAP-autocare-api/blob/main/compose.phase4.yaml). Configure `BILLING_DB_URL`, `BILLING_DB_USER`, `BILLING_DB_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_USER` e `RABBITMQ_PASSWORD` para iniciar o Billing fora do Compose.


A consulta interna `GET /internal/v1/budgets/{budgetId}` exige `X-Internal-Service-Token`, configurado com `BILLING_INTERNAL_TOKEN`. Para testar o provedor, use o cenário BDD da API. O ambiente com credenciais de teste do Mercado Pago exige `MERCADO_PAGO_ENABLED`, `MERCADO_PAGO_ACCESS_TOKEN`, `MERCADO_PAGO_EXTERNAL_POS_ID`, `MERCADO_PAGO_WEBHOOK_SECRET` e a rota HTTPS `/webhooks/mercado-pago`. [Orders QR](https://www.mercadopago.com.br/developers/pt/docs/qr-code/payment-processing)
