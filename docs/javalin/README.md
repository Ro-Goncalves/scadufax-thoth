# Javalin Curado

Documentação curada a partir da consulta feita em 2026-05-27.

## Fonte

* https://javalin.io/documentation

## Ponto de partida adotado

* Versão de referência observada na documentação: `7.2.2`
* Requisito mínimo da linha atual: Java 17+
* Estratégia adotada no projeto: usar Java 25 LTS, Javalin 7 e bootstrap mínimo com Maven.

## Snippets úteis para este repositório

### Dependência Maven

```xml
<dependency>
    <groupId>io.javalin</groupId>
    <artifactId>javalin</artifactId>
    <version>7.2.2</version>
</dependency>
```

### Criação da aplicação

```java
Javalin.create(config -> {
    config.routes.get("/", ctx -> ctx.result("Hello World"));
}).start(7070);
```

### Observações relevantes

* Em Javalin 7, as rotas devem ser declaradas dentro do bloco de criação da aplicação.
* O framework expõe `config.concurrency.useVirtualThreads` para ativar Virtual Threads quando disponíveis na JRE.
* Javalin usa Jackson como mapper JSON padrão quando a dependência está no classpath.
* A documentação recomenda adicionar uma implementação SLF4J, por exemplo `slf4j-simple`, quando necessário.
* Javalin roda sobre Jetty embutido e não precisa de application server externo.

## Aplicação no projeto

Para o bootstrap inicial do Scadufax Thoth, esta documentação foi usada para definir:

* dependência principal do framework;
* uso de Virtual Threads no bootstrap;
* ausência de rotas na primeira estrutura, mantendo apenas o servidor inicializável;
* configuração explícita do mapper JSON via Javalin quando necessário.