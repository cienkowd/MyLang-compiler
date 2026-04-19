# Kompilator MyLang — Etap 1

Kompilator prostego języka MyLang generujący kod LLVM IR.  
Zbudowany w Javie przy użyciu ANTLR4 jako generatora lexera/parsera.

> Szczegółowy opis działania kompilatora: [`ARCHITEKTURA.md`](ARCHITEKTURA.md)

---

## Szybki start

### 1. Pobierz ANTLR4 jar

```bash
wget https://www.antlr.org/download/antlr-4.13.1-complete.jar
# lub pobierz ręcznie z https://www.antlr.org/download.html
```

### 2. Zainstaluj LLVM (opcjonalnie — do uruchamiania wygenerowanego IR)

```bash
sudo apt install llvm   # Ubuntu/Debian
brew install llvm       # macOS
```

### 3. Zbuduj kompilator

```bash
make
```

### 4. Uruchom przykładowy program

```bash
make run FILE=tests/test_basic.ml
```

### 5. Uruchom wszystkie testy

```bash
make test
```

---

## Składnia języka MyLang

### Typy danych

| Słowo kluczowe | Opis                              | Typ LLVM  |
|----------------|-----------------------------------|-----------|
| `int`          | Liczba całkowita 32-bit (signed)  | `i32`     |
| `real`         | Zmiennoprzecinkowa 64-bit         | `double`  |
| `float32`      | Zmiennoprzecinkowa 32-bit IEEE754 | `float`   |
| `float64`      | Zmiennoprzecinkowa 64-bit IEEE754 | `double`  |
| `bool`         | Wartość logiczna (true/false)     | `i1`      |
| `string`       | Ciąg znaków (max 255 znaków)      | `i8*`     |

### Deklaracja zmiennych

```
int x               // deklaracja bez inicjalizacji
int y = 5           // deklaracja z inicjalizacją
real pi = 3.14
float32 f = 1.5
float64 d = 2.71828
bool flag = true
string s = "hello"
```

### Wejście / wyjście

```
read x              // wczytaj wartość ze stdin
print x             // wypisz wartość + nowa linia
print x + y * 2     // wypisz wynik wyrażenia
```

### Arytmetyka

```
x = 2 + 3 * 4       // kolejność działań: 14
x = (2 + 3) * 4     // nawiasy: 20
x = 10 / 3          // dzielenie całkowite: 3
r = 10.0 / 3.0      // dzielenie zmiennoprzecinkowe: 3.333...
x = -y              // jednoargumentowy minus
```

### Operacje logiczne (z short-circuit dla AND i OR)

```
bool a = true
bool b = false

bool r1 = a AND b           // false (AND: jeśli lewa=false → prawa pomijana)
bool r2 = a OR b            // true  (OR:  jeśli lewa=true  → prawa pomijana)
bool r3 = a XOR b           // true  (XOR: zawsze obie strony obliczane)
bool r4 = NOT a             // false
```

### Porównania (zwracają bool)

```
bool gt  = x > y
bool lt  = x < y
bool eq  = x == y
bool ne  = x != y
bool gte = x >= y
bool lte = x <= y
```

### Rzutowanie typów

```
real r  = (real) 5          // int → real
int  i  = (int) 3.14        // real → int (obcięcie w kierunku zera)
float32 f = (float32) r     // double → float (zawężenie, możliwa utrata precyzji)
float64 d = (float64) f     // float → double (poszerzenie, bezstratne)
bool b  = (bool) x          // int → bool (0=false, inne=true)
```

### Tablice o stałym rozmiarze

```
// Tablica 0-based (indeksy 0..N-1)
int[5] tab
tab[0] = 10
tab[4] = 50

// Tablica z zakresem indeksów (indeksy a..b)
int[1..5] ranged
ranged[1] = 100
ranged[5] = 500

// Dynamiczny indeks (zmienna)
int i = 2
tab[i] = tab[i] * 2

// Wypisanie wszystkich elementów
print tab

// Odczyt elementu ze stdin
read tab[0]

// Tablice innych typów
real[3] fvals
fvals[0] = 1.5
```

### Komentarze

```
// komentarz jednolinijkowy
```

---

## Obsługiwane wymagania

### Wymagania minimalne (10 pkt)

- Typy `int` i `real`
- I/O: `read` i `print`
- Operacje arytmetyczne z poprawną kolejnością działań (`+`, `-`, `*`, `/`, nawiasy)
- Obsługa błędów leksykalno-składniowych (ANTLR + własny listener)
- Obsługa błędów semantycznych (niezadeklarowane zmienne, niezgodność typów)

### Rozszerzenia

- **Short-circuit boolean evaluation (5 pkt)** — AND/OR przez warunkowe skoki + phi
- **Liczby o różnej precyzji: `float32`, `float64` (3 pkt)** — osobne typy LLVM
- **Typ `string` (3 pkt)** — deklaracja, `print`, `read`
- **Tablice z zakresami indeksów (3+1 pkt)**
  - `int[N] arr` — tablica 0-based
  - `int[a..b] arr` — tablica z dowolnym zakresem indeksów
  - `arr[i] = expr` — przypisanie elementu (indeks może być wyrażeniem)
  - `print arr` — wypisanie wszystkich elementów
  - `read arr[i]` — wczytanie elementu ze stdin
- **Operatory porównania** — `==`, `!=`, `<`, `>`, `<=`, `>=` zwracające `bool`

**Łącznie: do 25 pkt**

---

## Polecenia Makefile

```bash
make                        # zbuduj kompilator (gen + kompilacja Java)
make run FILE=tests/test_basic.ml  # kompiluj i uruchom program
make test                   # uruchom wszystkie pliki z tests/
make ir FILE=plik.ml        # tylko wypisz wygenerowany IR (bez uruchamiania)
make clean                  # usuń pliki budowania
```
