#include "stdio.h"

int main() {
    int i = 0;
    int trash = 100;
    int answer = 7;

    while (i < 5) {
        trash = trash + 1;
        i++;
    }

    return answer;
}
