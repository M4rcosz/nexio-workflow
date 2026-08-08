package com.nexio.workflow.infrastructure.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * Faz a conexao sair para o endereco que a validacao de SSRF aprovou, e nao para o que o DNS
 * responder na hora de conectar.
 *
 * <p><b>O problema.</b> O {@link HttpTargetValidator} resolve o host e confere que todo endereco
 * devolvido e publico. Depois disso o cliente HTTP abre a conexao -- e resolve o nome <i>de novo</i>.
 * Sao duas consultas, e quem controla o servidor autoritativo do dominio controla as duas: basta
 * publicar o registro com TTL zero e devolver um endereco publico na primeira consulta e
 * {@code 169.254.169.254} na segunda. A validacao passa, a conexao vai para os metadados da nuvem,
 * e nada no meio percebe. Isso e DNS rebinding, e nao e cenario teorico: e a forma padrao de
 * derrotar validacao de URL feita antes da conexao.</p>
 *
 * <p><b>A correcao.</b> Resolver uma vez so. O executor valida, guarda aqui os enderecos aprovados
 * e faz a requisicao; quando o cliente pede a resolucao do host, esta classe devolve exatamente
 * aqueles enderecos em vez de consultar a rede. A segunda consulta deixa de existir, e com ela a
 * janela.</p>
 *
 * <p><b>Por que o TLS continua valendo.</b> O que se fixa e o endereco, nao o nome: a requisicao
 * continua sendo feita para o host original, entao o SNI e a verificacao de certificado continuam
 * usando o nome que o autor do workflow escreveu. Um atacante que redirecione o endereco nao ganha
 * um certificado valido para aquele nome.</p>
 *
 * <p><b>Por que {@link ThreadLocal}.</b> A execucao de um workflow e sincrona (ADR 0005): a
 * validacao, a fixacao e a requisicao acontecem todas na mesma thread, e execucoes simultaneas
 * ficam em threads diferentes sem enxergar uma a outra. E tambem o motivo de a limpeza ser
 * obrigatoria e ficar em {@code finally}: threads de servidor sao reaproveitadas entre requisicoes,
 * e um mapa esquecido aqui faria a proxima execucao daquela thread conectar no endereco validado
 * para <i>outro</i> workflow.</p>
 *
 * <p><b>Fora de uma execucao fixada, o comportamento e o normal.</b> Sem nada guardado, a resolucao
 * e delegada ao resolvedor padrao do sistema. Isso importa porque o mesmo cliente pode ser usado
 * por caminho que nao passa pelo executor, e porque o custo de errar aqui seria quebrar toda
 * resolucao de nome da aplicacao.</p>
 */
public class PinnedDnsResolver implements DnsResolver {

    private static final ThreadLocal<Map<String, InetAddress[]>> PINNED = new ThreadLocal<>();

    private final DnsResolver delegate;

    /** Cria o resolvedor delegando ao padrao do sistema quando nao ha fixacao ativa. */
    public PinnedDnsResolver() {
        this(SystemDefaultDnsResolver.INSTANCE);
    }

    /**
     * Cria o resolvedor com um delegado explicito.
     *
     * @param delegate resolvedor usado quando o host nao esta fixado
     */
    public PinnedDnsResolver(DnsResolver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate nao pode ser nulo");
    }

    /**
     * Fixa, para a thread atual, os enderecos ja aprovados de um host.
     *
     * <p>Quem chama <b>tem</b> que chamar {@link #clear()} em {@code finally}. Ver a nota sobre
     * reaproveitamento de thread no cabecalho da classe.</p>
     *
     * @param host      host exatamente como aparece na URL
     * @param addresses enderecos aprovados pela validacao, nao vazio
     */
    public static void pin(String host, List<InetAddress> addresses) {
        Objects.requireNonNull(host, "host nao pode ser nulo");
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("A fixacao precisa de pelo menos um endereco");
        }
        PINNED.set(Map.of(normalize(host), addresses.toArray(InetAddress[]::new)));
    }

    /** Remove a fixacao da thread atual. Idempotente. */
    public static void clear() {
        PINNED.remove();
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        Map<String, InetAddress[]> pinned = PINNED.get();
        InetAddress[] addresses = pinned == null ? null : pinned.get(normalize(host));
        if (addresses == null) {
            return delegate.resolve(host);
        }
        // Copia na saida: o array e entregue ao cliente HTTP, e devolver o interno deixaria quem
        // recebe alterar a fixacao de dentro para fora.
        return addresses.clone();
    }

    /**
     * Nunca resolve nome canonico.
     *
     * <p>A resolucao canonica e uma consulta reversa que sai para a rede e cujo resultado nao passa
     * por validacao nenhuma. Devolver o proprio host mantem a requisicao amarrada ao nome que o
     * autor escreveu, que e o mesmo nome que o certificado precisa apresentar.</p>
     */
    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    /** Host de DNS nao diferencia maiuscula de minuscula; a chave da fixacao tambem nao pode. */
    private static String normalize(String host) {
        return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
    }
}
