Pensei, e cheguei numa hesitação que vale explicar — porque ela mudou o que eu acho que falta no report.

O tipo da exceção já classifica boa parte disso de graça. `NullPointerException`, `SocketTimeoutException`, `SerializationException` — o modelo lê isso nativamente e já sabe qual estratégia seguir. Colocar `tipo: null-pointer` por cima é redundante na maioria dos casos.

O problema é o resto dos casos. **Classificação errada é pior que classificação nenhuma:** se eu marco "falha externa" e na verdade era config, eu ancorei o assistente na trilha errada logo na primeira linha — e modelo é bem suscetível a enquadramento inicial. Numa heurística baseada em tipo de exceção, o erro vai acontecer justamente nos casos interessantes: o `SocketTimeoutException` que é pool de conexão esgotado por vazamento, não rede.

Tem um dado que me deixou mais cauteloso ainda. A Microsoft publicou um trabalho de RCA automatizada em incidentes de cloud (RCACopilot, EuroSys '24) onde eles testaram adicionar os comentários de discussão do incidente como contexto extra — coisa que contém o raciocínio humano do diagnóstico. **Não melhorou a performance de forma significativa.** O que ganha não é volume de contexto, é pouco sinal decisivo. Uma categoria a mais é volume.

Onde eu acho que classificação ganha de verdade é no que **não** dá pra inferir do stack trace:

- **"isso é novo?"** — hoje o report sabe que o erro aconteceu na build `7e3c1f`, mas não sabe se ele *começou* ali. O campo de recorrência que existe é escopo de sessão: reseta a cada restart. Então a primeira pergunta de qualquer triagem — *foi a minha mudança?* — é justamente a que o formato não responde. Está aberto como issue e é o que eu considero o buraco mais caro.
- **"só acontece sob concorrência"** — essa realmente não está em lugar nenhum do trace.

E tem uma classificação que eu já faço e que acho que é a que importa: o `← YOUR CODE`. Ela responde *de quem é o problema*, que também não é inferível do tipo da exceção — num stack de 70 frames com Spring e Tomcat no meio, é a diferença entre o assistente olhar pro seu código ou gastar turnos investigando o framework. Aliás essa é frágil de um jeito que eu não esperava: descobri semana passada que um proxy CGLIB do Spring mora no pacote da sua aplicação, então ele era escolhido como "seu código" mesmo sendo classe gerada sem fonte nenhuma.

Se tu tiver um caso concreto onde o tipo da exceção enganou e uma categoria teria salvado o diagnóstico, esse é o argumento que me faria mudar de ideia — e aí seria uma issue com um caso real em vez de uma hipótese, que é bem melhor.
