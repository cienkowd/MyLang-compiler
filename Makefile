# ──────────────────────────────────────────────────────────────────────────────
# Makefile dla kompilatora MyLang
#
# Wymagania:
#   - Java JDK 11+  (sprawdź: java -version)
#   - ANTLR 4.13.1  (plik antlr-4.13.1-complete.jar w katalogu projektu)
#   - LLVM          (polecenia lli lub clang, sprawdź: lli --version)
#
# Pobranie ANTLR jar:
#   wget https://www.antlr.org/download/antlr-4.13.1-complete.jar
#   lub pobierz ręcznie z https://www.antlr.org/download.html
#
# Użycie:
#   make              – buduje kompilator
#   make run FILE=tests/test_basic.ml  – kompiluje i uruchamia program
#   make test         – uruchamia wszystkie testy
#   make clean        – czyści pliki budowania
# ──────────────────────────────────────────────────────────────────────────────

ANTLR_JAR   = antlr-4.13.1-complete.jar
ANTLR_CLASS = org.antlr.v4.Tool
SRC_DIR     = src
GEN_DIR     = src/gen
BUILD_DIR   = build
GRAMMAR     = $(SRC_DIR)/MyLang.g4

# Klaspath do uruchomienia: jar ANTLR + skompilowane klasy
CP          = $(ANTLR_JAR):$(BUILD_DIR)

# Domyślny plik do uruchomienia
FILE        ?= tests/test_basic.ml

.PHONY: all gen compile run test clean check-antlr

# ── Cel domyślny ───────────────────────────────────────────────────────────────
all: check-antlr compile
	@echo ""
	@echo "✓ Kompilator gotowy. Uruchom:"
	@echo "  make run FILE=tests/test_basic.ml"

# ── Sprawdź obecność ANTLR jar ────────────────────────────────────────────────
check-antlr:
	@if [ ! -f "$(ANTLR_JAR)" ]; then \
		echo ""; \
		echo "BŁĄD: brak pliku $(ANTLR_JAR)"; \
		echo "Pobierz go z: https://www.antlr.org/download/antlr-4.13.1-complete.jar"; \
		echo "  wget https://www.antlr.org/download/antlr-4.13.1-complete.jar"; \
		echo ""; \
		exit 1; \
	fi

# ── Generowanie kodu z gramatyki ──────────────────────────────────────────────
# Uruchamiamy ANTLR z poziomu src/ żeby pliki trafiły do gen/ bez zagnieżdżenia
gen: check-antlr
	@echo "→ Generowanie lexera/parsera z gramatyki..."
	@mkdir -p $(GEN_DIR)
	cd $(SRC_DIR) && java -jar ../$(ANTLR_JAR) -visitor -no-listener \
	     -o ../$(GEN_DIR) \
	     MyLang.g4 2>&1
	@echo "→ Wygenerowane pliki:"
	@find $(GEN_DIR) -name "*.java" 2>/dev/null | sort
	@test $$(find $(GEN_DIR) -name "*.java" 2>/dev/null | wc -l) -gt 0 || \
	    (echo "BŁĄD: ANTLR nie wygenerował plików .java. Sprawdź błędy powyżej." && exit 1)
	@echo "✓ Generowanie zakończone"

# ── Kompilacja Java ───────────────────────────────────────────────────────────
compile: gen
	@echo "→ Kompilacja Java..."
	@mkdir -p $(BUILD_DIR)
	javac -cp "$(ANTLR_JAR):$(GEN_DIR)" \
	      -d $(BUILD_DIR) \
	      $$(find $(GEN_DIR) -name "*.java") \
	      $$(find $(SRC_DIR) -maxdepth 1 -name "*.java")
	@echo "✓ Kompilacja zakończona pomyślnie"

# ── Uruchomienie: kompilacja pliku .ml → LLVM IR → wykonanie ─────────────────
run: all
	@echo ""
	@echo "═══ Kompilacja: $(FILE) ═══"
	java -cp "$(CP)" Main $(FILE) > /tmp/out.ll
	@echo "─── Wygenerowany LLVM IR ───"
	@cat /tmp/out.ll
	@echo ""
	@echo "─── Wynik wykonania ───"
	@if command -v lli >/dev/null 2>&1; then \
		lli /tmp/out.ll; \
	elif command -v clang >/dev/null 2>&1; then \
		clang /tmp/out.ll -o /tmp/out_bin && /tmp/out_bin; \
	else \
		echo "Uwaga: lli/clang nie znalezione. Wygenerowany IR: /tmp/out.ll"; \
		echo "Zainstaluj LLVM: sudo apt install llvm"; \
	fi

# ── Tylko generuj IR (bez wykonywania) ────────────────────────────────────────
ir: all
	@java -cp "$(CP)" Main $(FILE)

# ── Testy ─────────────────────────────────────────────────────────────────────
test: all
	@echo "═══ Uruchamianie testów ═══"
	@for f in tests/*.ml; do \
		echo ""; \
		echo "--- Test: $$f ---"; \
		java -cp "$(CP)" Main $$f > /tmp/test_out.ll 2>/tmp/test_err.ll; \
		if [ $$? -ne 0 ]; then \
			echo "BŁĄD KOMPILACJI:"; \
			cat /tmp/test_err.ll; \
		else \
			echo "IR OK, rozmiar: $$(wc -l < /tmp/test_out.ll) linii"; \
		fi; \
	done
	@echo ""
	@echo "═══ Testy zakończone ═══"

# ── Sprzątanie ────────────────────────────────────────────────────────────────
clean:
	@rm -rf $(BUILD_DIR) $(GEN_DIR)
	@echo "✓ Wyczyszczono pliki budowania"
