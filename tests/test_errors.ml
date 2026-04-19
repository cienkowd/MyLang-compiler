// Test 6: błędy semantyczne – ten plik POWINIEN się nie skompilować
// Każda linia to inny błąd:

// Użycie niezadeklarowanej zmiennej
// print niezadeklarowana

// Niezgodność typów przy operacji
// int x = 5
// bool b = true
// int z = x + b

// Podwójna deklaracja
// int x = 5
// int x = 10

// Aby przetestować błędy: odkomentuj jedną linię na raz
// i sprawdź czy kompilator prawidłowo zgłasza błąd

// Ten plik z zakomentowanymi błędami kompiluje się poprawnie:
int a = 42
print a
