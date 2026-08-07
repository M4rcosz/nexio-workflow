package com.nexio.workflow.application.usecase.command;

import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowNode;
import java.util.List;
import java.util.Objects;

/**
 * Dados de entrada da atualizacao parcial de um workflow.
 *
 * <p>Todo campo e um {@link Patch}: o que o chamador nao enviou permanece como esta no agregado
 * carregado, e o que ele enviou e aplicado, inclusive quando o valor enviado e nulo. Ver
 * {@link Patch} para o motivo de nao ser "nulo significa nao mexer".</p>
 *
 * <p>Nao existe componente {@code id}: o identificador do alvo e parametro do caso de uso, nao
 * campo do comando, para nao abrir a possibilidade de um comando cujo id discorde do id pedido.
 * Tambem nao existe {@code version}: o agregado nao expoe {@code setVersion} justamente porque uma
 * versao vinda do cliente anularia o bloqueio otimista.</p>
 *
 * <p>{@code startNodeId} e atualizavel junto com {@code nodes} por necessidade, e nao por
 * simetria: trocar o conjunto de nos sem poder trocar o no inicial deixaria o {@code startNodeId}
 * antigo apontando para um no que nao existe mais, e a validacao do grafo recusaria toda
 * atualizacao desse tipo, sem que houvesse qualquer forma de sair do impasse.</p>
 *
 * @param name          novo nome
 * @param description   nova descricao, aceita nulo para limpar o campo
 * @param enabled       novo estado de habilitacao
 * @param triggerConfig nova configuracao de gatilho
 * @param nodes         novo grafo de nos; copiado na construcao
 * @param startNodeId   novo no inicial, aceita nulo para voltar a resolucao automatica
 */
public record UpdateWorkflowCommand(
        Patch<String> name,
        Patch<String> description,
        Patch<Boolean> enabled,
        Patch<TriggerConfig> triggerConfig,
        Patch<List<WorkflowNode>> nodes,
        Patch<String> startNodeId
) {

    public UpdateWorkflowCommand {
        Objects.requireNonNull(name, "name nao pode ser nulo; use Patch.unchanged()");
        Objects.requireNonNull(description, "description nao pode ser nula; use Patch.unchanged()");
        Objects.requireNonNull(enabled, "enabled nao pode ser nulo; use Patch.unchanged()");
        Objects.requireNonNull(triggerConfig, "triggerConfig nao pode ser nulo; use Patch.unchanged()");
        Objects.requireNonNull(nodes, "nodes nao pode ser nulo; use Patch.unchanged()");
        Objects.requireNonNull(startNodeId, "startNodeId nao pode ser nulo; use Patch.unchanged()");
        nodes = copyNodes(nodes);
    }

    private static Patch<List<WorkflowNode>> copyNodes(Patch<List<WorkflowNode>> nodes) {
        if (!nodes.present() || nodes.value() == null) {
            return nodes;
        }
        return Patch.of(List.copyOf(nodes.value()));
    }

    /**
     * Cria um construtor fluente com todos os campos intocados.
     *
     * <p>Existe porque o caso comum e alterar um campo entre seis, e o construtor canonico obrigaria
     * a escrever cinco {@code Patch.unchanged()} em toda chamada -- exatamente o tipo de ruido em
     * que um argumento acaba na posicao errada.</p>
     *
     * @return construtor sem nenhum campo marcado para alteracao
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Construtor fluente de {@link UpdateWorkflowCommand}. */
    public static final class Builder {

        private Patch<String> name = Patch.unchanged();
        private Patch<String> description = Patch.unchanged();
        private Patch<Boolean> enabled = Patch.unchanged();
        private Patch<TriggerConfig> triggerConfig = Patch.unchanged();
        private Patch<List<WorkflowNode>> nodes = Patch.unchanged();
        private Patch<String> startNodeId = Patch.unchanged();

        private Builder() {
        }

        /**
         * Marca o nome para alteracao.
         *
         * @param value novo nome
         * @return este construtor
         */
        public Builder name(String value) {
            this.name = Patch.of(value);
            return this;
        }

        /**
         * Marca a descricao para alteracao.
         *
         * @param value nova descricao, pode ser nula para limpar o campo
         * @return este construtor
         */
        public Builder description(String value) {
            this.description = Patch.of(value);
            return this;
        }

        /**
         * Marca o estado de habilitacao para alteracao.
         *
         * @param value novo estado
         * @return este construtor
         */
        public Builder enabled(boolean value) {
            this.enabled = Patch.of(value);
            return this;
        }

        /**
         * Marca a configuracao de gatilho para alteracao.
         *
         * @param value nova configuracao
         * @return este construtor
         */
        public Builder triggerConfig(TriggerConfig value) {
            this.triggerConfig = Patch.of(value);
            return this;
        }

        /**
         * Marca o grafo de nos para alteracao.
         *
         * @param value novos nos
         * @return este construtor
         */
        public Builder nodes(List<WorkflowNode> value) {
            this.nodes = Patch.of(value);
            return this;
        }

        /**
         * Marca o no inicial para alteracao.
         *
         * @param value novo no inicial, pode ser nulo para voltar a resolucao automatica
         * @return este construtor
         */
        public Builder startNodeId(String value) {
            this.startNodeId = Patch.of(value);
            return this;
        }

        /**
         * Monta o comando.
         *
         * @return comando com os campos marcados
         */
        public UpdateWorkflowCommand build() {
            return new UpdateWorkflowCommand(name, description, enabled, triggerConfig, nodes, startNodeId);
        }
    }
}
