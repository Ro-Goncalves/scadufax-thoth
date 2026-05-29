# Configuração de Testes: Acesso à Memória Nativa

Como o núcleo do nosso motor de busca baseia-se na **FFM API (Foreign Function & Memory API)** do Java 21+ para manipular memória *Off-Heap*, enfrentamos um desafio arquitetural na hora de escrever testes unitários.

Para testar o cálculo matemático, nós simulamos um trecho de memória nativa utilizando o `Arena.ofConfined()`. No entanto, para que esse teste rode com sucesso automatizado via Maven, foi necessário incluir uma configuração específica no **Maven Surefire Plugin**.

## O Desafio: A Segurança Padrão da JVM

Historicamente, o Java orgulha-se de ser uma linguagem segura. Isso significa que é praticamente impossível um desenvolvedor Java causar um *Segmentation Fault* acessando um endereço de memória inválido, algo comum em linguagens como C ou C++.

Quando a FFM API foi introduzida, ela deu ao Java o "poder" do C, mas com isso veio a responsabilidade.

* **O Comportamento Padrão:** Para evitar que bibliotecas maliciosas ou mal escritas destruam a memória da aplicação, **o Java 21+ bloqueia o acesso à memória nativa por padrão**.
* Se o seu código tentar chamar `Arena.ofConfined()` ou acessar um `MemorySegment` sem autorização explícita, a JVM lançará um alerta severo ou uma exceção fatal, interrompendo a execução.

## A Solução: O Papel do Maven Surefire Plugin

No ecossistema Java, quando executamos o comando `mvn test` no terminal ou em uma pipeline de CI/CD, o Maven não roda os testes no mesmo processo em que foi chamado. Ele delega essa tarefa para o **Maven Surefire Plugin**, que cria uma **nova JVM** dedicada exclusivamente para rodar os testes.

Como essa nova JVM nasce com as configurações de segurança padrão, precisamos injetar a permissão diretamente no momento em que ela é criada.

É por isso que adicionamos a seguinte configuração no nosso `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>${maven.surefire.plugin.version}</version>
    <configuration>
        <argLine>--enable-native-access=ALL-UNNAMED</argLine>
    </configuration>
</plugin>

```

### O que significa `--enable-native-access=ALL-UNNAMED`?

* **`--enable-native-access`**: Esta é a "chave do cofre". É a flag que informa à JVM: *"Eu sei o que estou fazendo. Permita que este processo manipule a memória do sistema operacional diretamente."*
* **`ALL-UNNAMED`**: O Java moderno possui um sistema de módulos rígido. Como o nosso projeto não está encapsulado num módulo nomeado formal, todo o nosso código reside no chamado "módulo não-nomeado" (Unnamed Module). Ao passar `ALL-UNNAMED`, estamos dizendo à JVM para conceder a permissão de acesso nativo a todo o nosso código de aplicação e testes.

### Resumo para a Engenharia

Sem essa flag no Surefire, seus testes podem até passar na sua IDE, mas **vão quebrar na esteira de integração contínua**. Essa configuração garante que a infraestrutura de testes simule com fidelidade o ambiente de produção, permitindo a validação da nossa matemática *Zero-Copy* com total segurança e previsibilidade.