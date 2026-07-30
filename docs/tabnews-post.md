Toda vez que eu colava um stack trace do Java num assistente, acontecia a mesma coisa: ele respondia com uma pergunta. *"Qual era o valor de X?"*, *"tem o log de antes do erro?"*, *"qual versão do Java?"*. Cinco, dez mensagens até chegar no diagnóstico — perguntando por coisas que **existiam no momento do erro e foram jogadas fora**.

Minha reação instintiva foi colar mais log. Resolvi medir se isso ajuda.

## O experimento

Montei um cenário que roda como teste no CI: um checkout que falha enquanto outras três requisições estão em andamento. Isso importa — é a razão pela qual, num log real, a linha que **explica** a falha nunca está do lado do stack trace.

Aí, pra cada artefato que eu poderia colar, verifiquei se ele contém os cinco fatos necessários pra consertar o bug sem fazer nenhuma pergunta de volta:

1. a causa raiz
2. se a minha classe aparece
3. **por que** o valor estava nulo
4. os valores envolvidos
5. o ambiente (versão do Java, profile)

| O que a IA lê | Linhas | ≈ Tokens | Responde? |
|---|---:|---:|---|
| Stack trace sozinho | 89 | 2 376 | 3 de 5 |
| Stack trace + 200 linhas de log | 290 | 7 565 | **3 de 5** |
| `app.log` inteiro da sessão | 395 | 10 129 | 4 de 5 |
| Report estruturado | 29 | 463 | 5 de 5 |

## A linha do meio é a resposta

**Pagar 200 linhas a mais de log não trouxe nenhum fato novo.**

O tráfego concorrente já tinha empurrado a linha do cache-miss pra fora da janela. Você triplica o custo em token e continua sem a informação que faltava — então o assistente pergunta de novo. É o loop de interrogação, reproduzido num teste.

Isso também explica por que **pós-processamento não resolve**. Quando o log é escrito, a história já está espalhada entre threads. Não dá pra remontar depois o que não foi capturado junto.

## O que eu acabei fazendo

Escrevi um appender de Logback que, além do log normal, escreve um segundo arquivo com a falha já estruturada: causa raiz primeiro, o frame que é **seu** marcado, os eventos daquela thread/trace que antecederam o erro, MDC, argumentos, ambiente. O log humano fica intacto.

```xml
<dependency>
  <groupId>io.github.gabrielbbaldez</groupId>
  <artifactId>stacktale-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

No Spring Boot é zero configuração. Também tem adapter pra Log4j2 e JUL, e um servidor MCP pro assistente ler os reports como ferramenta em vez de você copiar e colar.

## O que ele não faz

Vale ser honesto sobre os limites, porque eles são reais:

- **Se a sua aplicação não loga nada antes do erro, não existe história pra contar.** A ferramenta organiza o que você já registrou; ela não inventa contexto.
- Os valores das variáveis no momento do throw só aparecem com um `-javaagent` opcional. Estado do objeto ainda não.
- O arquivo carrega MDC e argumentos de log. Tem redação de segredos por padrão, mas é dado de request — não commita.
- É single-JVM e local. Correlação entre serviços não é o problema que ele resolve.

## Sobre os números

Tokens são a aproximação usual de `caracteres / 4`, aplicada igualmente em todas as linhas — o que vale é a razão entre elas, não o valor absoluto. O teste está no repositório e roda com um comando, se quiser conferir ou rodar contra o seu próprio log:

```bash
mvn -pl stacktale test -Dtest=EfficiencyBenchmarkTest
```

---

Duas coisas que eu queria ouvir de quem lê:

**A lista de cinco fatos está certa?** Escolhi ela a partir do que os assistentes ficavam me perguntando. Se falta um sexto, isso muda o que o report deveria carregar.

**Alguém aqui já mediu isso de outro jeito?** Achei bastante coisa sobre reduzir custo de token e quase nada sobre o log conter ou não a resposta, que me parece a métrica que importa de verdade.
