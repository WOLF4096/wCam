#include "demosaic.h"
#include <vector>
#include <algorithm>

void demosaic_vng(const DemosaicParams& p, void* pixels) {
    demosaic_ahd(p, pixels);
}
