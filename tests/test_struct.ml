struct Point {
    int x
    int y
}

Point p
p.x = 10
p.y = 20
print p.x
print p.y
int dist = p.x + p.y
print dist

struct Rect {
    int width
    int height
}

Rect r
r.width = 5
r.height = 8
int area = r.width * r.height
print area
