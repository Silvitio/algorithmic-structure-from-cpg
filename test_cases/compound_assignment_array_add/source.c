#include "stdio.h"

int main() {
    int arr[3] = {1, 2, 3};
    int i = 1;
    int x = 4;

    arr[i] += x;

    return arr[i];
}
