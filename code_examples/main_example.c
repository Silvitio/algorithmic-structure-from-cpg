 #include "stdio.h"

int main() {
    int sum;
    int arr[5] = {0, 1, 2, 3, 4};

    for (int i = 0; i < 0; i++) {
        sum = sum + arr[i];
    }

    printf("%d", sum);
    return 0;
}




