float32 f = 1.1
float64 d = 1.1
real    r = 1.1

print f
print d
print r

int i = 2
float32 result32 = (float32) i + f
print result32

float64 result64 = (float64) i + d
print result64

float64 promoted = (float64) f
print promoted

float32 narrowed = (float32) d
print narrowed
