// Test 7: tablice z zakresami indeksów
// Oczekiwany wynik:
//   10       ← tab[0]
//   20       ← tab[1]
//   30       ← tab[2]
//   40       ← tab[3]
//   50       ← tab[4]
//   150      ← suma elementów
//   100      ← ranged[1]
//   200      ← ranged[2]
//   300      ← ranged[3]
//   60       ← tab[2] po podwojeniu
//   1.500000 ← fvals[0]
//   2.500000 ← fvals[1]
//   3.500000 ← fvals[2]

// ── Tablica 0-based (int[5] tab) ─────────────────────────────────────────────
int[5] tab
tab[0] = 10
tab[1] = 20
tab[2] = 30
tab[3] = 40
tab[4] = 50

// Wypisz wszystkie elementy
print tab

// Suma elementów
int suma = tab[0] + tab[1] + tab[2] + tab[3] + tab[4]
print suma

// ── Tablica z zakresem indeksów (int[1..3] ranged) ──────────────────────────
int[1..3] ranged
ranged[1] = 100
ranged[2] = 200
ranged[3] = 300

// Wypisz wszystkie elementy
print ranged

// ── Modyfikacja elementu przez wyrażenie z indeksem zmienną ─────────────────
int i = 2
tab[i] = tab[i] * 2
print tab[i]

// ── Tablica zmiennoprzecinkowa (real[3] fvals) ───────────────────────────────
real[3] fvals
fvals[0] = 1.5
fvals[1] = 2.5
fvals[2] = 3.5

// Wypisz wszystkie elementy
print fvals
