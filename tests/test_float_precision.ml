// Test 3: różna precyzja liczb zmiennoprzecinkowych
// float32 ma ~7 cyfr precyzji (uwaga na błędy zaokrąglenia)
// float64/real ma ~15 cyfr precyzji

float32 f = 1.1
float64 d = 1.1
real    r = 1.1

// Wydruk: wszystkie powinny wypisać 1.100000 lub bliskie wartości
// float32 może mieć niewielki błąd zaokrąglenia
print f
print d
print r

// Działania na różnych precyzjach
// int + float32 → float32
int i = 2
float32 result32 = (float32) i + f
print result32

// int + float64 → float64
float64 result64 = (float64) i + d
print result64

// Rzutowanie float32 → float64 (poszerzenie)
float64 promoted = (float64) f
print promoted

// Rzutowanie float64 → float32 (zawężenie – możliwa utrata precyzji)
float32 narrowed = (float32) d
print narrowed
