# Logos por tenant (etiqueta de volume)

Coloque aqui o logo de cada cliente com o nome do **slug do tenant**:

```
negri.png
modial.png
```

A página de etiqueta (`/etiquetas/...`) tenta `/<slug>.png` e, se não achar, `/<slug>.jpg`.
Sem arquivo → a etiqueta sai sem logo (sem erro).

Formato recomendado: PNG com fundo transparente, até ~600×160 px (a etiqueta limita a
`1.6cm` de altura / `6cm` de largura na impressão).
