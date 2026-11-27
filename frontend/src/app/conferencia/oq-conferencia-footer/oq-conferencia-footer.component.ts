import { Component, HostListener, EventEmitter, Input, Output } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';

@Component({
  selector: 'oq-conferencia-footer',
  standalone: true,
  imports: [OqIconComponent, OqSpinnerComponent],
  templateUrl: './oq-conferencia-footer.component.html',
  styleUrl: './oq-conferencia-footer.component.scss',
})
export class OqConferenciaFooterComponent {
  @Input() divergenceCount = 0;
  @Input() pendingCount = 0;
  @Input() conferredCount = 0;
  /** Itens que já bateram o total (não conta parcial) — numerador da barra PROGRESSO. */
  @Input() progressoFeito = 0;
  /** Total de itens distintos (parcial não conta 2x) — denominador da barra PROGRESSO. */
  @Input() progressoTotal = 0;
  /** Conferência às cegas (CCO EXIBIRQTD = 'N'): sem esperado nem divergência durante a contagem. */
  @Input() cega = false;
  @Input() canConfirm = false;
  /** true = requisição de confirmar/concluir etapa em voo — mostra spinner no botão primário. */
  @Input() confirmando = false;
  /** CCO.FORMACAOVOLUMES 'S'/'T'/'D' — exige volume > 0; destaca o contador quando ainda zerado. */
  @Input() exigeVolume = false;
  /** Rótulo do botão primário — "Confirmar Conferência" (normal) ou "Concluir Etapa X" (conferência por etapa). */
  @Input() confirmarLabel = 'Confirmar conferência';
  /** Rótulo do botão de sair — "Voltar" (normal) ou "Salvar e sair" (conferência por etapa: progresso persiste). */
  @Input() voltarLabel = 'Voltar';
  /** Modo simplificado (sem dimensão) — só a quantidade de volumes do pedido, nativo do Sankhya. */
  @Input() volume = 0;

  /** Paletes do recebimento (27/11/2026): botão "Fechar palete" só na compra; texto do que está lido sem palete. */
  @Input() mostrarPalete = false;
  @Input() paleteAberto: string | null = null;
  @Input() fechandoPalete = false;
  @Output() fecharPalete = new EventEmitter<void>();

  @Output() voltar = new EventEmitter<void>();
  @Output() confirmar = new EventEmitter<void>();
  @Output() volumeChange = new EventEmitter<number>();
  @Output() cancelar = new EventEmitter<void>();
  /** Mostra o botão "Ver conferidos" (sessão já iniciada). */
  @Input() mostrarVerConferidos = false;
  @Output() verConferidos = new EventEmitter<void>();
  /** "Atualizar com Sankhya" — relê o pedido e corrige os itens da sessão. */
  @Output() sincronizar = new EventEmitter<void>();
  @Input() sincronizando = false;
  /** "Atualizar com Sankhya" no menu ⚙ (precisa de sessão aberta). */
  @Input() mostrarSincronizar = false;
  /** Menu ⚙ (Atualizar com Sankhya / Cancelar pedido) aberto. */
  maisAberto = false;
  /** Coletor: passo "Quantos volumes?" aberto (opção A — volumes ao confirmar). */
  passoVolume = false;
  /** Tela de coletor (≤639px) — rodapé só com ⚙ e Confirmar. */
  coletor = OqConferenciaFooterComponent.ehColetor();

  private static ehColetor(): boolean {
    return typeof window !== 'undefined' && !!window.matchMedia?.('(max-width: 639px)').matches;
  }

  @HostListener('window:resize')
  aoRedimensionar(): void {
    this.coletor = OqConferenciaFooterComponent.ehColetor();
  }

  /** "Ver conferidos" no menu ⚙ do coletor (sempre que há sessão). */
  @Input() podeVerConferidosNoMenu = false;

  /**
   * No coletor com formação de volumes o Confirmar abre o passo de volumes — então fica habilitado mesmo com
   * volume 0 (canConfirm só fica falso por volume ou envio em andamento; o passo exige volume > 0).
   */
  get podeTocarConfirmar(): boolean {
    return this.canConfirm || (this.coletor && this.exigeVolume);
  }

  onConfirmarClick(event: MouseEvent): void {
    if (!this.soPonteiro(event)) return;
    if (this.coletor && this.exigeVolume) {
      this.passoVolume = true;
      return;
    }
    this.confirmar.emit();
  }

  confirmarDoPasso(): void {
    if (this.volume === 0 || this.confirmando) return;
    this.passoVolume = false;
    this.confirmar.emit();
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  fecharMais(): void {
    this.maisAberto = false;
  }

  /** "Atualizar com Sankhya" do menu ⚙ — mesma guarda do botão antigo (soPonteiro) e fecha o menu. */
  sincronizarDoMenu(ev: Event): void {
    if (!this.soPonteiro(ev as MouseEvent)) return;
    this.maisAberto = false;
    this.sincronizar.emit();
  }

  /**
   * true só pra clique/toque de verdade. Enter/Espaço num botão com foco também
   * geram "click" (com detail === 0) — e o leitor de código de barras termina
   * toda leitura com Enter. Caso real (nota 57735): o botão "Concluir Etapa"
   * ficou com foco, o Enter de um bipe acionou a conclusão da última etapa e
   * abriu o pop-up de finalização divergente com itens ainda não bipados.
   * Mesmo guarda no +/− de volumes (o bipe somava volume sozinho).
   */
  soPonteiro(event: MouseEvent): boolean {
    (event.currentTarget as HTMLElement | null)?.blur();
    return event.detail > 0;
  }

  onVolumeMenos(): void {
    if (this.volume > 0) this.volumeChange.emit(this.volume - 1);
  }

  onVolumeMais(): void {
    this.volumeChange.emit(this.volume + 1);
  }

  selecionarTudo(event: FocusEvent): void {
    (event.target as HTMLInputElement).select();
  }

  /**
   * Valor digitado no campo de volumes. Inteiro de 0 a 999; qualquer outra coisa volta pro
   * valor atual. Enter confirma e tira o foco — o próximo bipe do leitor volta pro campo de
   * código de barras, não cai aqui.
   */
  confirmarVolumeDigitado(event: Event, enter: boolean): void {
    const campo = event.target as HTMLInputElement;
    const digitado = Number(campo.value);
    const valido = campo.value.trim() !== '' && Number.isInteger(digitado) && digitado >= 0 && digitado <= 999;
    if (!valido) {
      campo.value = String(this.volume);
    } else if (digitado !== this.volume) {
      this.volumeChange.emit(digitado);
    }
    if (enter) {
      event.preventDefault();
      event.stopPropagation();
      campo.blur();
    }
  }

  /** true = CCO exige volume mas o contador ainda está zerado — destaca e explica o botão desabilitado. */
  get faltaVolume(): boolean {
    return this.exigeVolume && this.volume === 0;
  }

  get tituloBotaoPrimario(): string {
    return this.faltaVolume ? 'Informe a quantidade de volumes antes de confirmar' : '';
  }

  get textoDivergencia(): string {
    const plural = this.divergenceCount > 1 ? 'S' : '';
    return `${this.divergenceCount} DIVERGÊNCIA${plural} PENDENTE${plural} DE RESOLUÇÃO`;
  }
}
