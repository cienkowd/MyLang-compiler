ANTLR_JAR = antlr-4.13.1-complete.jar
SRC_DIR   = src
GEN_DIR   = src/gen
BUILD_DIR = build
CP        = $(ANTLR_JAR):$(BUILD_DIR)
FILE     ?= tests/test_basic.ml

.PHONY: all gen compile run ir test clean

all: compile

gen:
	@[ -f "$(ANTLR_JAR)" ] || { echo "Brak $(ANTLR_JAR) – pobierz z https://www.antlr.org/download.html"; exit 1; }
	@mkdir -p $(GEN_DIR)
	cd $(SRC_DIR) && java -jar ../$(ANTLR_JAR) -visitor -no-listener -o ../$(GEN_DIR) MyLang.g4

compile: gen
	@mkdir -p $(BUILD_DIR)
	javac -cp "$(ANTLR_JAR):$(GEN_DIR)" -d $(BUILD_DIR) \
	    $$(find $(GEN_DIR) -maxdepth 1 -name "*.java") \
	    $$(find $(SRC_DIR) -maxdepth 1 -name "*.java")

run: all
	java -cp "$(CP)" Main $(FILE) > /tmp/out.ll
	@if command -v lli >/dev/null 2>&1; then \
		lli /tmp/out.ll; \
	elif command -v clang >/dev/null 2>&1; then \
		clang /tmp/out.ll -o /tmp/out_bin && /tmp/out_bin; \
	else \
		echo "Brak lli/clang. IR zapisany w /tmp/out.ll"; \
	fi

ir: all
	java -cp "$(CP)" Main $(FILE)

test: all
	@pass=0; fail=0; \
	for f in tests/*.ml; do \
		printf "%-42s" "$$f"; \
		if java -cp "$(CP)" Main $$f > /tmp/test_out.ll 2>/tmp/test_err.ll; then \
			echo "OK  ($$(wc -l < /tmp/test_out.ll) linii IR)"; \
			pass=$$((pass+1)); \
		else \
			echo "FAIL"; \
			sed 's/^/  /' /tmp/test_err.ll; \
			fail=$$((fail+1)); \
		fi; \
	done; \
	echo ""; \
	echo "Wynik: $$pass OK, $$fail FAIL"

clean:
	rm -rf $(BUILD_DIR) $(GEN_DIR)
