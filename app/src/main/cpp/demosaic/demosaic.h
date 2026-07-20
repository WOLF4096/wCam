#ifndef WCAM_DEMOSAIC_H
#define WCAM_DEMOSAIC_H

#include "demosaic_common.h"

void demosaic_bilinear(const DemosaicParams& p, void* pixels);
void demosaic_ahd(const DemosaicParams& p, void* pixels);
void demosaic_eahd(const DemosaicParams& p, void* pixels);
void demosaic_hphd(const DemosaicParams& p, void* pixels);
void demosaic_vng(const DemosaicParams& p, void* pixels);
void demosaic_rcd(const DemosaicParams& p, void* pixels);
void demosaic_amaze(const DemosaicParams& p, void* pixels);
void demosaic_lmmse(const DemosaicParams& p, void* pixels);
void demosaic_dcb(const DemosaicParams& p, void* pixels);
void demosaic_igv(const DemosaicParams& p, void* pixels);

#endif // WCAM_DEMOSAIC_H
