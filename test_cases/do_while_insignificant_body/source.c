#include "stdio.h"

int main() {
    int x = 3;
    int noise = 0;
    int answer = 42;

    do {
        noise = noise + x;
        x--;
    } while (x > 0);

    return answer;
}
