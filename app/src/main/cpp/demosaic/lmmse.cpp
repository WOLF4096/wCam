#include "demosaic.h"
#include <vector>
#include <algorithm>

void demosaic_lmmse(const DemosaicParams& p, void* pixels) {
    demosaic_ahd(p, pixels);
}
