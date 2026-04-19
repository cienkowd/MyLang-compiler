// Test 5: porównania (zwracają bool) + short-circuit w wyrażeniu złożonym
// Oczekiwane wyjście:
//   1   (5 > 3)
//   0   (5 == 3)
//   1   (5 >= 5)
//   1   (x > 0 AND y > 0 – oba spełnione)
//   0   (x < 0 OR y < 0 – żadne nie spełnione)

int x = 5
int y = 3

bool gt  = x > y
bool eq  = x == y
bool gte = x >= x
print gt
print eq
print gte

bool both_positive = x > 0 AND y > 0
print both_positive

bool any_negative = x < 0 OR y < 0
print any_negative
