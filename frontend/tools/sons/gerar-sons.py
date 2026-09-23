"""
Gera a biblioteca de sons do WMS (src/assets/sounds/*.wav).

Sons 100% sintetizados aqui (sem arquivo de terceiros) — mesma família do
SomFeedbackService original: onda quadrada + passa-baixa ~2200 Hz, beeps
curtos com ataque/soltura de poucos ms (sem "click"). Soa industrial/digital,
agudo e seco, nada de efeito de videogame ou toque de celular.

Todos os arquivos saem com o MESMO nível (RMS da parte sonora normalizado e
pico limitado) — a diferença de volume entre eventos é decidida em
action-feedback.config.ts (campo `volume`), nunca no arquivo.

Uso (da pasta frontend/):  python tools/sons/gerar-sons.py
Requer numpy.
"""
import os
import wave

import numpy as np

SR = 22050            # mono 16-bit 22 kHz: sobra pra tons < 3 kHz e arquivo pequeno
ALVO_RMS = 0.5        # nível comum de todos os arquivos (parte com som) — próximo do som antigo (quadrada a 0.75)
PICO_MAX = 0.97       # ~ -0.3 dBFS
CORTE_LP = 2200.0     # mesmo passa-baixa do SomFeedbackService original
SAIDA = os.path.join(os.path.dirname(__file__), '..', '..', 'src', 'assets', 'sounds')


def beep(freq, dur, vol=1.0, onda='quadrada'):
    """Um beep com ataque 6 ms e soltura 12 ms (sem estalo)."""
    n = int(SR * dur)
    t = np.arange(n) / SR
    if onda == 'quadrada':
        s = np.sign(np.sin(2 * np.pi * freq * t))
    else:  # 'seno' — pro 'enviando' discreto
        s = np.sin(2 * np.pi * freq * t)
    env = np.ones(n)
    a, r = int(SR * 0.006), int(SR * 0.012)
    env[:a] = np.linspace(0, 1, a)
    env[-r:] = np.linspace(1, 0, r)
    return s * env * vol


def silencio(dur):
    return np.zeros(int(SR * dur))


def passa_baixa(x, fc=CORTE_LP):
    """2 polos simples em cascata — tira a aspereza da quadrada, como o BiquadFilter original."""
    alfa = 1 - np.exp(-2 * np.pi * fc / SR)
    for _ in range(2):
        y = np.zeros_like(x)
        acc = 0.0
        for i, v in enumerate(x):
            acc += alfa * (v - acc)
            y[i] = acc
        x = y
    return x


def normalizar(x):
    sonoro = x[np.abs(x) > 1e-3]
    rms = np.sqrt(np.mean(sonoro ** 2)) if len(sonoro) else 1.0
    x = x * (ALVO_RMS / rms)
    pico = np.max(np.abs(x))
    if pico > PICO_MAX:
        x = x * (PICO_MAX / pico)
    return x


def seq(*partes):
    return np.concatenate(partes)


SONS = {
    # Bipe/leitura: um tique agudo e curtíssimo — ouvido centenas de vezes, não pode cansar.
    'item-lido': seq(beep(1760, 0.045)),
    # Conferido: 2 beeps ascendentes (MESMO 'ok' de antes: 900 → 1200 Hz).
    'item-conferido': seq(beep(900, 0.13), silencio(0.03), beep(1200, 0.13)),
    # Precisa escolher o controle/lote: MESMO 'atencao' de antes (700 Hz).
    'atencao': seq(beep(700, 0.18)),
    # Peso capturado: confirmação curta, mais aguda que o 'atencao'.
    'pesagem-ok': seq(beep(1320, 0.09)),
    # Produto fora do pedido / incorreto: 2 beeps rápidos médios.
    'produto-incorreto': seq(beep(620, 0.07), silencio(0.05), beep(620, 0.07)),
    # Erro genérico: MESMO 'erro' de antes (descendente 300 → 260 Hz).
    'erro': seq(beep(300, 0.22), silencio(0.05), beep(260, 0.14)),
    # Divergência: alerta de 2 tons alternados (2 ciclos).
    'divergencia': seq(beep(880, 0.09), beep(660, 0.09), beep(880, 0.09), beep(660, 0.12)),
    # Corte liberado: confirmação curta e marcante (salto de quinta).
    'corte-liberado': seq(beep(1000, 0.07), silencio(0.02), beep(1500, 0.14)),
    # Finalização / todos conferidos: MESMO 'finalizado' de antes (800 → 1000 → 1300).
    'finalizacao': seq(beep(800, 0.12), silencio(0.03), beep(1000, 0.12), silencio(0.03), beep(1300, 0.18)),
    # Enviando pro Sankhya: pulso senoidal discreto (volume baixo vem da config).
    'enviando-sankhya': seq(beep(1500, 0.035, onda='seno'), silencio(0.06), beep(1500, 0.035, onda='seno')),
    # Sankhya confirmou: 2 tons ascendentes limpos (dó–mi agudos).
    'sucesso-sankhya': seq(beep(1047, 0.09), silencio(0.02), beep(1319, 0.16)),
    # Sankhya recusou / falha de comunicação: 3 tons descendentes.
    'erro-sankhya': seq(beep(600, 0.1), silencio(0.02), beep(450, 0.1), silencio(0.02), beep(300, 0.2)),
    # Bloqueado / não permitido: buzzer grave e curto.
    'bloqueado': seq(beep(190, 0.16)),
}


def gravar(nome, x):
    x = normalizar(passa_baixa(x))
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2')
    caminho = os.path.join(SAIDA, f'{nome}.wav')
    with wave.open(caminho, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return caminho, len(x) / SR


if __name__ == '__main__':
    os.makedirs(SAIDA, exist_ok=True)
    for nome, sinal in SONS.items():
        caminho, dur = gravar(nome, sinal)
        print(f'{nome:18s} {dur * 1000:5.0f} ms  {os.path.getsize(caminho):6d} B')
