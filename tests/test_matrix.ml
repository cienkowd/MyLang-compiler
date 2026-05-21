int[3][4] mat

for i = 0 to 3 {
    for j = 0 to 4 {
        mat[i][j] = i * 4 + j
    }
}

print mat

int[2][2] a
a[0][0] = 1
a[0][1] = 2
a[1][0] = 3
a[1][1] = 4

int trace = a[0][0] + a[1][1]
print trace

int[2][3] b
b[0][0] = 1
b[0][1] = 2
b[0][2] = 3
b[1][0] = 4
b[1][1] = 5
b[1][2] = 6

for row = 0 to 2 {
    print b[row][0]
    print b[row][1]
    print b[row][2]
}

for col = 0 to 3 {
    print b[0][col]
    print b[1][col]
}
