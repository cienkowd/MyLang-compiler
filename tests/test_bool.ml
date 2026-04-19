// Test 2: wartości logiczne z short-circuit evaluation
// Oczekiwane wyjście (0=false, 1=true):
//   0   (true AND false = false)
//   1   (false OR true = true)
//   1   (true XOR false = true)
//   0   (NOT true = false)
//   1   (short-circuit: true OR <obliczenie> – prawa strona NIE jest obliczana)
//   0   (short-circuit: false AND <obliczenie> – prawa strona NIE jest obliczana)

bool a = true
bool b = false

bool r1 = a AND b
print r1

bool r2 = b OR a
print r2

bool r3 = a XOR b
print r3

bool r4 = NOT a
print r4

// Short-circuit OR: jeśli lewa=true, prawa nie jest obliczana
bool r5 = a OR NOT a
print r5

// Short-circuit AND: jeśli lewa=false, prawa nie jest obliczana
bool r6 = b AND NOT b
print r6
